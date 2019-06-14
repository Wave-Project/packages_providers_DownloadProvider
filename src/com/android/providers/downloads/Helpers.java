/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.downloads;

import static android.os.Environment.buildExternalStorageAppDataDirs;
import static android.os.Environment.buildExternalStorageAppMediaDirs;
import static android.os.Environment.buildExternalStorageAppObbDirs;
import static android.os.Environment.buildExternalStoragePublicDirs;
import static android.provider.Downloads.Impl.DESTINATION_EXTERNAL;
import static android.provider.Downloads.Impl.DESTINATION_FILE_URI;
import static android.provider.Downloads.Impl.DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD;
import static android.provider.Downloads.Impl.FLAG_REQUIRES_CHARGING;
import static android.provider.Downloads.Impl.FLAG_REQUIRES_DEVICE_IDLE;

import static com.android.providers.downloads.Constants.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Downloads;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.webkit.MimeTypeMap;

import com.android.internal.util.ArrayUtils;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Some helper functions for the download manager
 */
public class Helpers {
    public static Random sRandom = new Random(SystemClock.uptimeMillis());

    /** Regex used to parse content-disposition headers */
    private static final Pattern CONTENT_DISPOSITION_PATTERN =
            Pattern.compile("attachment;\\s*filename\\s*=\\s*\"([^\"]*)\"");

    private static final Pattern PATTERN_ANDROID_DIRS =
            Pattern.compile("(?i)^/storage/[^/]+(?:/[0-9]+)?/Android/(?:data|obb|media)/.+");

    private static final Pattern PATTERN_PUBLIC_DIRS =
            Pattern.compile("(?i)^/storage/[^/]+(?:/[0-9]+)?/([^/]+)/.+");

    private static final Object sUniqueLock = new Object();

    private static HandlerThread sAsyncHandlerThread;
    private static Handler sAsyncHandler;

    private static SystemFacade sSystemFacade;
    private static DownloadNotifier sNotifier;

    private Helpers() {
    }

    public synchronized static Handler getAsyncHandler() {
        if (sAsyncHandlerThread == null) {
            sAsyncHandlerThread = new HandlerThread("sAsyncHandlerThread",
                    Process.THREAD_PRIORITY_BACKGROUND);
            sAsyncHandlerThread.start();
            sAsyncHandler = new Handler(sAsyncHandlerThread.getLooper());
        }
        return sAsyncHandler;
    }

    @VisibleForTesting
    public synchronized static void setSystemFacade(SystemFacade systemFacade) {
        sSystemFacade = systemFacade;
    }

    public synchronized static SystemFacade getSystemFacade(Context context) {
        if (sSystemFacade == null) {
            sSystemFacade = new RealSystemFacade(context);
        }
        return sSystemFacade;
    }

    public synchronized static DownloadNotifier getDownloadNotifier(Context context) {
        if (sNotifier == null) {
            sNotifier = new DownloadNotifier(context);
        }
        return sNotifier;
    }

    public static String getString(Cursor cursor, String col) {
        return cursor.getString(cursor.getColumnIndexOrThrow(col));
    }

    public static int getInt(Cursor cursor, String col) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(col));
    }

    public static void scheduleJob(Context context, long downloadId) {
        final boolean scheduled = scheduleJob(context,
                DownloadInfo.queryDownloadInfo(context, downloadId));
        if (!scheduled) {
            // If we didn't schedule a future job, kick off a notification
            // update pass immediately
            getDownloadNotifier(context).update();
        }
    }

    /**
     * Schedule (or reschedule) a job for the given {@link DownloadInfo} using
     * its current state to define job constraints.
     */
    public static boolean scheduleJob(Context context, DownloadInfo info) {
        if (info == null) return false;

        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);

        // Tear down any existing job for this download
        final int jobId = (int) info.mId;
        scheduler.cancel(jobId);

        // Skip scheduling if download is paused or finished
        if (!info.isReadyToSchedule()) return false;

        final JobInfo.Builder builder = new JobInfo.Builder(jobId,
                new ComponentName(context, DownloadJobService.class));

        // When this download will show a notification, run with a higher
        // priority, since it's effectively a foreground service
        if (info.isVisible()) {
            builder.setPriority(JobInfo.PRIORITY_FOREGROUND_SERVICE);
            builder.setFlags(JobInfo.FLAG_WILL_BE_FOREGROUND);
        }

        // We might have a backoff constraint due to errors
        final long latency = info.getMinimumLatency();
        if (latency > 0) {
            builder.setMinimumLatency(latency);
        }

        // We always require a network, but the type of network might be further
        // restricted based on download request or user override
        builder.setRequiredNetworkType(info.getRequiredNetworkType(info.mTotalBytes));

        if ((info.mFlags & FLAG_REQUIRES_CHARGING) != 0) {
            builder.setRequiresCharging(true);
        }
        if ((info.mFlags & FLAG_REQUIRES_DEVICE_IDLE) != 0) {
            builder.setRequiresDeviceIdle(true);
        }

        // Provide estimated network size, when possible
        if (info.mTotalBytes > 0) {
            if (info.mCurrentBytes > 0 && !TextUtils.isEmpty(info.mETag)) {
                // If we're resuming an in-progress download, we only need to
                // download the remaining bytes.
                builder.setEstimatedNetworkBytes(info.mTotalBytes - info.mCurrentBytes,
                        JobInfo.NETWORK_BYTES_UNKNOWN);
            } else {
                builder.setEstimatedNetworkBytes(info.mTotalBytes, JobInfo.NETWORK_BYTES_UNKNOWN);
            }
        }

        // If package name was filtered during insert (probably due to being
        // invalid), blame based on the requesting UID instead
        String packageName = info.mPackage;
        if (packageName == null) {
            packageName = context.getPackageManager().getPackagesForUid(info.mUid)[0];
        }

        scheduler.scheduleAsPackage(builder.build(), packageName, UserHandle.myUserId(), TAG);
        return true;
    }

    /*
     * Parse the Content-Disposition HTTP Header. The format of the header
     * is defined here: http://www.w3.org/Protocols/rfc2616/rfc2616-sec19.html
     * This header provides a filename for content that is going to be
     * downloaded to the file system. We only support the attachment type.
     */
    private static String parseContentDisposition(String contentDisposition) {
        try {
            Matcher m = CONTENT_DISPOSITION_PATTERN.matcher(contentDisposition);
            if (m.find()) {
                return m.group(1);
            }
        } catch (IllegalStateException ex) {
             // This function is defined as returning null when it can't parse the header
        }
        return null;
    }

    /**
     * Creates a filename (where the file should be saved) from info about a download.
     * This file will be touched to reserve it.
     */
    static String generateSaveFile(Context context, String url, String hint,
            String contentDisposition, String contentLocation, String mimeType, int destination)
            throws IOException {

        final File parent;
        final File[] parentTest;
        String name = null;

        if (destination == Downloads.Impl.DESTINATION_FILE_URI) {
            final File file = new File(Uri.parse(hint).getPath());
            parent = file.getParentFile().getAbsoluteFile();
            parentTest = new File[] { parent };
            name = file.getName();
        } else {
            parent = getRunningDestinationDirectory(context, destination);
            parentTest = new File[] {
                    parent,
                    getSuccessDestinationDirectory(context, destination)
            };
            name = chooseFilename(url, hint, contentDisposition, contentLocation);
        }

        // Ensure target directories are ready
        for (File test : parentTest) {
            if (!(test.isDirectory() || test.mkdirs())) {
                throw new IOException("Failed to create parent for " + test);
            }
        }

        if (DownloadDrmHelper.isDrmConvertNeeded(mimeType)) {
            name = DownloadDrmHelper.modifyDrmFwLockFileExtension(name);
        }

        final String prefix;
        final String suffix;
        final int dotIndex = name.lastIndexOf('.');
        final boolean missingExtension = dotIndex < 0;
        if (destination == Downloads.Impl.DESTINATION_FILE_URI) {
            // Destination is explicitly set - do not change the extension
            if (missingExtension) {
                prefix = name;
                suffix = "";
            } else {
                prefix = name.substring(0, dotIndex);
                suffix = name.substring(dotIndex);
            }
        } else {
            // Split filename between base and extension
            // Add an extension if filename does not have one
            if (missingExtension) {
                prefix = name;
                suffix = chooseExtensionFromMimeType(mimeType, true);
            } else {
                prefix = name.substring(0, dotIndex);
                suffix = chooseExtensionFromFilename(mimeType, destination, name, dotIndex);
            }
        }

        synchronized (sUniqueLock) {
            name = generateAvailableFilenameLocked(parentTest, prefix, suffix);

            // Claim this filename inside lock to prevent other threads from
            // clobbering us. We're not paranoid enough to use O_EXCL.
            final File file = new File(parent, name);
            file.createNewFile();
            return file.getAbsolutePath();
        }
    }

    private static String chooseFilename(String url, String hint, String contentDisposition,
            String contentLocation) {
        String filename = null;

        // First, try to use the hint from the application, if there's one
        if (filename == null && hint != null && !hint.endsWith("/")) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "getting filename from hint");
            }
            int index = hint.lastIndexOf('/') + 1;
            if (index > 0) {
                filename = hint.substring(index);
            } else {
                filename = hint;
            }
        }

        // If we couldn't do anything with the hint, move toward the content disposition
        if (filename == null && contentDisposition != null) {
            filename = parseContentDisposition(contentDisposition);
            if (filename != null) {
                if (Constants.LOGVV) {
                    Log.v(Constants.TAG, "getting filename from content-disposition");
                }
                int index = filename.lastIndexOf('/') + 1;
                if (index > 0) {
                    filename = filename.substring(index);
                }
            }
        }

        // If we still have nothing at this point, try the content location
        if (filename == null && contentLocation != null) {
            String decodedContentLocation = Uri.decode(contentLocation);
            if (decodedContentLocation != null
                    && !decodedContentLocation.endsWith("/")
                    && decodedContentLocation.indexOf('?') < 0) {
                if (Constants.LOGVV) {
                    Log.v(Constants.TAG, "getting filename from content-location");
                }
                int index = decodedContentLocation.lastIndexOf('/') + 1;
                if (index > 0) {
                    filename = decodedContentLocation.substring(index);
                } else {
                    filename = decodedContentLocation;
                }
            }
        }

        // If all the other http-related approaches failed, use the plain uri
        if (filename == null) {
            String decodedUrl = Uri.decode(url);
            if (decodedUrl != null
                    && !decodedUrl.endsWith("/") && decodedUrl.indexOf('?') < 0) {
                int index = decodedUrl.lastIndexOf('/') + 1;
                if (index > 0) {
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "getting filename from uri");
                    }
                    filename = decodedUrl.substring(index);
                }
            }
        }

        // Finally, if couldn't get filename from URI, get a generic filename
        if (filename == null) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "using default filename");
            }
            filename = Constants.DEFAULT_DL_FILENAME;
        }

        // The VFAT file system is assumed as target for downloads.
        // Replace invalid characters according to the specifications of VFAT.
        filename = FileUtils.buildValidFatFilename(filename);

        return filename;
    }

    private static String chooseExtensionFromMimeType(String mimeType, boolean useDefaults) {
        String extension = null;
        if (mimeType != null) {
            extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (extension != null) {
                if (Constants.LOGVV) {
                    Log.v(Constants.TAG, "adding extension from type");
                }
                extension = "." + extension;
            } else {
                if (Constants.LOGVV) {
                    Log.v(Constants.TAG, "couldn't find extension for " + mimeType);
                }
            }
        }
        if (extension == null) {
            if (mimeType != null && mimeType.toLowerCase().startsWith("text/")) {
                if (mimeType.equalsIgnoreCase("text/html")) {
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "adding default html extension");
                    }
                    extension = Constants.DEFAULT_DL_HTML_EXTENSION;
                } else if (useDefaults) {
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "adding default text extension");
                    }
                    extension = Constants.DEFAULT_DL_TEXT_EXTENSION;
                }
            } else if (useDefaults) {
                if (Constants.LOGVV) {
                    Log.v(Constants.TAG, "adding default binary extension");
                }
                extension = Constants.DEFAULT_DL_BINARY_EXTENSION;
            }
        }
        return extension;
    }

    private static String chooseExtensionFromFilename(String mimeType, int destination,
            String filename, int lastDotIndex) {
        String extension = null;
        if (mimeType != null) {
            // Compare the last segment of the extension against the mime type.
            // If there's a mismatch, discard the entire extension.
            String typeFromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    filename.substring(lastDotIndex + 1));
            if (typeFromExt == null || !typeFromExt.equalsIgnoreCase(mimeType)) {
                extension = chooseExtensionFromMimeType(mimeType, false);
                if (extension != null) {
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "substituting extension from type");
                    }
                } else {
                    if (Constants.LOGVV) {
                        Log.v(Constants.TAG, "couldn't find extension for " + mimeType);
                    }
                }
            }
        }
        if (extension == null) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "keeping extension");
            }
            extension = filename.substring(lastDotIndex);
        }
        return extension;
    }

    private static boolean isFilenameAvailableLocked(File[] parents, String name) {
        if (Constants.RECOVERY_DIRECTORY.equalsIgnoreCase(name)) return false;

        for (File parent : parents) {
            if (new File(parent, name).exists()) {
                return false;
            }
        }

        return true;
    }

    private static String generateAvailableFilenameLocked(
            File[] parents, String prefix, String suffix) throws IOException {
        String name = prefix + suffix;
        if (isFilenameAvailableLocked(parents, name)) {
            return name;
        }

        /*
        * This number is used to generate partially randomized filenames to avoid
        * collisions.
        * It starts at 1.
        * The next 9 iterations increment it by 1 at a time (up to 10).
        * The next 9 iterations increment it by 1 to 10 (random) at a time.
        * The next 9 iterations increment it by 1 to 100 (random) at a time.
        * ... Up to the point where it increases by 100000000 at a time.
        * (the maximum value that can be reached is 1000000000)
        * As soon as a number is reached that generates a filename that doesn't exist,
        *     that filename is used.
        * If the filename coming in is [base].[ext], the generated filenames are
        *     [base]-[sequence].[ext].
        */
        int sequence = 1;
        for (int magnitude = 1; magnitude < 1000000000; magnitude *= 10) {
            for (int iteration = 0; iteration < 9; ++iteration) {
                name = prefix + Constants.FILENAME_SEQUENCE_SEPARATOR + sequence + suffix;
                if (isFilenameAvailableLocked(parents, name)) {
                    return name;
                }
                sequence += sRandom.nextInt(magnitude) + 1;
            }
        }

        throw new IOException("Failed to generate an available filename");
    }

    public static boolean isFileInExternalAndroidDirs(String filePath) {
        return PATTERN_ANDROID_DIRS.matcher(filePath).matches();
    }

    static boolean isFilenameValid(Context context, File file) {
        return isFilenameValid(context, file, true);
    }

    static boolean isFilenameValidInExternal(Context context, File file) {
        return isFilenameValid(context, file, false);
    }

    /**
     * Test if given file exists in one of the package-specific external storage
     * directories that are always writable to apps, regardless of storage
     * permission.
     */
    static boolean isFilenameValidInExternalPackage(Context context, File file,
            String packageName) {
        try {
            if (containsCanonical(buildExternalStorageAppDataDirs(packageName), file) ||
                    containsCanonical(buildExternalStorageAppObbDirs(packageName), file) ||
                    containsCanonical(buildExternalStorageAppMediaDirs(packageName), file)) {
                return true;
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to resolve canonical path: " + e);
            return false;
        }

        return false;
    }

    static boolean isFilenameValidInPublicDownloadsDir(File file) {
        try {
            if (containsCanonical(buildExternalStoragePublicDirs(
                    Environment.DIRECTORY_DOWNLOADS), file)) {
                return true;
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to resolve canonical path: " + e);
            return false;
        }

        return false;
    }

    @com.android.internal.annotations.VisibleForTesting
    public static boolean isFilenameValidInKnownPublicDir(@Nullable String filePath) {
        if (filePath == null) {
            return false;
        }
        final Matcher matcher = PATTERN_PUBLIC_DIRS.matcher(filePath);
        if (matcher.matches()) {
            final String publicDir = matcher.group(1);
            return ArrayUtils.contains(Environment.STANDARD_DIRECTORIES, publicDir);
        }
        return false;
    }

    /**
     * Checks whether the filename looks legitimate for security purposes. This
     * prevents us from opening files that aren't actually downloads.
     */
    static boolean isFilenameValid(Context context, File file, boolean allowInternal) {
        try {
            if (allowInternal) {
                if (containsCanonical(context.getFilesDir(), file)
                        || containsCanonical(context.getCacheDir(), file)
                        || containsCanonical(Environment.getDownloadCacheDirectory(), file)) {
                    return true;
                }
            }

            final StorageVolume[] volumes = StorageManager.getVolumeList(UserHandle.myUserId(),
                    StorageManager.FLAG_FOR_WRITE);
            for (StorageVolume volume : volumes) {
                if (containsCanonical(volume.getPathFile(), file)) {
                    return true;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to resolve canonical path: " + e);
            return false;
        }

        return false;
    }

    private static boolean containsCanonical(File dir, File file) throws IOException {
        return FileUtils.contains(dir.getCanonicalFile(), file);
    }

    private static boolean containsCanonical(File[] dirs, File file) throws IOException {
        for (File dir : dirs) {
            if (containsCanonical(dir, file)) {
                return true;
            }
        }
        return false;
    }

    public static File getRunningDestinationDirectory(Context context, int destination)
            throws IOException {
        return getDestinationDirectory(context, destination, true);
    }

    public static File getSuccessDestinationDirectory(Context context, int destination)
            throws IOException {
        return getDestinationDirectory(context, destination, false);
    }

    private static File getDestinationDirectory(Context context, int destination, boolean running)
            throws IOException {
        switch (destination) {
            case Downloads.Impl.DESTINATION_CACHE_PARTITION:
            case Downloads.Impl.DESTINATION_CACHE_PARTITION_PURGEABLE:
            case Downloads.Impl.DESTINATION_CACHE_PARTITION_NOROAMING:
                if (running) {
                    return context.getFilesDir();
                } else {
                    return context.getCacheDir();
                }

            case Downloads.Impl.DESTINATION_EXTERNAL:
                final File target = new File(
                        Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS);
                if (!target.isDirectory() && target.mkdirs()) {
                    throw new IOException("unable to create external downloads directory");
                }
                return target;

            default:
                throw new IllegalStateException("unexpected destination: " + destination);
        }
    }

    public static void handleRemovedUidEntries(@NonNull Context context, @NonNull Cursor cursor,
            @NonNull ArrayList<Long> idsToDelete, @NonNull ArrayList<Long> idsToOrphan,
            @Nullable LongSparseArray<String> idsToGrantPermission) {
        final SparseArray<String> knownUids = new SparseArray<>();
        while (cursor.moveToNext()) {
            final long downloadId = cursor.getLong(0);
            final int uid = cursor.getInt(1);

            final String ownerPackageName;
            final int index = knownUids.indexOfKey(uid);
            if (index >= 0) {
                ownerPackageName = knownUids.valueAt(index);
            } else {
                ownerPackageName = getPackageForUid(context, uid);
                knownUids.put(uid, ownerPackageName);
            }

            if (ownerPackageName == null) {
                final int destination = cursor.getInt(2);
                final String filePath = cursor.getString(3);

                if ((destination == DESTINATION_EXTERNAL
                        || destination == DESTINATION_FILE_URI
                        || destination == DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD)
                        && isFilenameValidInKnownPublicDir(filePath)) {
                    idsToOrphan.add(downloadId);
                } else {
                    idsToDelete.add(downloadId);
                }
            } else if (idsToGrantPermission != null) {
                idsToGrantPermission.put(downloadId, ownerPackageName);
            }
        }
    }

    public static String buildQueryWithIds(ArrayList<Long> downloadIds) {
        final StringBuilder queryBuilder = new StringBuilder(Downloads.Impl._ID + " in (");
        final int size = downloadIds.size();
        for (int i = 0; i < size; i++) {
            queryBuilder.append(downloadIds.get(i));
            queryBuilder.append((i == size - 1) ? ")" : ",");
        }
        return queryBuilder.toString();
    }

    public static String getPackageForUid(Context context, int uid) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            return null;
        }
        // For permission related purposes, any package belonging to the given uid should work.
        return packages[0];
    }

    /**
     * Checks whether this looks like a legitimate selection parameter
     */
    public static void validateSelection(String selection, Set<String> allowedColumns) {
        try {
            if (selection == null || selection.isEmpty()) {
                return;
            }
            Lexer lexer = new Lexer(selection, allowedColumns);
            parseExpression(lexer);
            if (lexer.currentToken() != Lexer.TOKEN_END) {
                throw new IllegalArgumentException("syntax error");
            }
        } catch (RuntimeException ex) {
            if (Constants.LOGV) {
                Log.d(Constants.TAG, "invalid selection [" + selection + "] triggered " + ex);
            } else if (false) {
                Log.d(Constants.TAG, "invalid selection triggered " + ex);
            }
            throw ex;
        }

    }

    // expression <- ( expression ) | statement [AND_OR ( expression ) | statement] *
    //             | statement [AND_OR expression]*
    private static void parseExpression(Lexer lexer) {
        for (;;) {
            // ( expression )
            if (lexer.currentToken() == Lexer.TOKEN_OPEN_PAREN) {
                lexer.advance();
                parseExpression(lexer);
                if (lexer.currentToken() != Lexer.TOKEN_CLOSE_PAREN) {
                    throw new IllegalArgumentException("syntax error, unmatched parenthese");
                }
                lexer.advance();
            } else {
                // statement
                parseStatement(lexer);
            }
            if (lexer.currentToken() != Lexer.TOKEN_AND_OR) {
                break;
            }
            lexer.advance();
        }
    }

    // statement <- COLUMN COMPARE VALUE
    //            | COLUMN IS NULL
    private static void parseStatement(Lexer lexer) {
        // both possibilities start with COLUMN
        if (lexer.currentToken() != Lexer.TOKEN_COLUMN) {
            throw new IllegalArgumentException("syntax error, expected column name");
        }
        lexer.advance();

        // statement <- COLUMN COMPARE VALUE
        if (lexer.currentToken() == Lexer.TOKEN_COMPARE) {
            lexer.advance();
            if (lexer.currentToken() != Lexer.TOKEN_VALUE) {
                throw new IllegalArgumentException("syntax error, expected quoted string");
            }
            lexer.advance();
            return;
        }

        // statement <- COLUMN IS NULL
        if (lexer.currentToken() == Lexer.TOKEN_IS) {
            lexer.advance();
            if (lexer.currentToken() != Lexer.TOKEN_NULL) {
                throw new IllegalArgumentException("syntax error, expected NULL");
            }
            lexer.advance();
            return;
        }

        // didn't get anything good after COLUMN
        throw new IllegalArgumentException("syntax error after column name");
    }

    /**
     * A simple lexer that recognizes the words of our restricted subset of SQL where clauses
     */
    private static class Lexer {
        public static final int TOKEN_START = 0;
        public static final int TOKEN_OPEN_PAREN = 1;
        public static final int TOKEN_CLOSE_PAREN = 2;
        public static final int TOKEN_AND_OR = 3;
        public static final int TOKEN_COLUMN = 4;
        public static final int TOKEN_COMPARE = 5;
        public static final int TOKEN_VALUE = 6;
        public static final int TOKEN_IS = 7;
        public static final int TOKEN_NULL = 8;
        public static final int TOKEN_END = 9;

        private final String mSelection;
        private final Set<String> mAllowedColumns;
        private int mOffset = 0;
        private int mCurrentToken = TOKEN_START;
        private final char[] mChars;

        public Lexer(String selection, Set<String> allowedColumns) {
            mSelection = selection;
            mAllowedColumns = allowedColumns;
            mChars = new char[mSelection.length()];
            mSelection.getChars(0, mChars.length, mChars, 0);
            advance();
        }

        public int currentToken() {
            return mCurrentToken;
        }

        public void advance() {
            char[] chars = mChars;

            // consume whitespace
            while (mOffset < chars.length && chars[mOffset] == ' ') {
                ++mOffset;
            }

            // end of input
            if (mOffset == chars.length) {
                mCurrentToken = TOKEN_END;
                return;
            }

            // "("
            if (chars[mOffset] == '(') {
                ++mOffset;
                mCurrentToken = TOKEN_OPEN_PAREN;
                return;
            }

            // ")"
            if (chars[mOffset] == ')') {
                ++mOffset;
                mCurrentToken = TOKEN_CLOSE_PAREN;
                return;
            }

            // "?"
            if (chars[mOffset] == '?') {
                ++mOffset;
                mCurrentToken = TOKEN_VALUE;
                return;
            }

            // "=" and "=="
            if (chars[mOffset] == '=') {
                ++mOffset;
                mCurrentToken = TOKEN_COMPARE;
                if (mOffset < chars.length && chars[mOffset] == '=') {
                    ++mOffset;
                }
                return;
            }

            // ">" and ">="
            if (chars[mOffset] == '>') {
                ++mOffset;
                mCurrentToken = TOKEN_COMPARE;
                if (mOffset < chars.length && chars[mOffset] == '=') {
                    ++mOffset;
                }
                return;
            }

            // "<", "<=" and "<>"
            if (chars[mOffset] == '<') {
                ++mOffset;
                mCurrentToken = TOKEN_COMPARE;
                if (mOffset < chars.length && (chars[mOffset] == '=' || chars[mOffset] == '>')) {
                    ++mOffset;
                }
                return;
            }

            // "!="
            if (chars[mOffset] == '!') {
                ++mOffset;
                mCurrentToken = TOKEN_COMPARE;
                if (mOffset < chars.length && chars[mOffset] == '=') {
                    ++mOffset;
                    return;
                }
                throw new IllegalArgumentException("Unexpected character after !");
            }

            // columns and keywords
            // first look for anything that looks like an identifier or a keyword
            //     and then recognize the individual words.
            // no attempt is made at discarding sequences of underscores with no alphanumeric
            //     characters, even though it's not clear that they'd be legal column names.
            if (isIdentifierStart(chars[mOffset])) {
                int startOffset = mOffset;
                ++mOffset;
                while (mOffset < chars.length && isIdentifierChar(chars[mOffset])) {
                    ++mOffset;
                }
                String word = mSelection.substring(startOffset, mOffset);
                if (mOffset - startOffset <= 4) {
                    if (word.equals("IS")) {
                        mCurrentToken = TOKEN_IS;
                        return;
                    }
                    if (word.equals("OR") || word.equals("AND")) {
                        mCurrentToken = TOKEN_AND_OR;
                        return;
                    }
                    if (word.equals("NULL")) {
                        mCurrentToken = TOKEN_NULL;
                        return;
                    }
                }
                if (mAllowedColumns.contains(word)) {
                    mCurrentToken = TOKEN_COLUMN;
                    return;
                }
                throw new IllegalArgumentException("unrecognized column or keyword: " + word);
            }

            // quoted strings
            if (chars[mOffset] == '\'') {
                ++mOffset;
                while (mOffset < chars.length) {
                    if (chars[mOffset] == '\'') {
                        if (mOffset + 1 < chars.length && chars[mOffset + 1] == '\'') {
                            ++mOffset;
                        } else {
                            break;
                        }
                    }
                    ++mOffset;
                }
                if (mOffset == chars.length) {
                    throw new IllegalArgumentException("unterminated string");
                }
                ++mOffset;
                mCurrentToken = TOKEN_VALUE;
                return;
            }

            // anything we don't recognize
            throw new IllegalArgumentException("illegal character: " + chars[mOffset]);
        }

        private static final boolean isIdentifierStart(char c) {
            return c == '_' ||
                    (c >= 'A' && c <= 'Z') ||
                    (c >= 'a' && c <= 'z');
        }

        private static final boolean isIdentifierChar(char c) {
            return c == '_' ||
                    (c >= 'A' && c <= 'Z') ||
                    (c >= 'a' && c <= 'z') ||
                    (c >= '0' && c <= '9');
        }
    }
}
