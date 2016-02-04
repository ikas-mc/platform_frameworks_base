/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.mtp;

import android.database.Cursor;
import android.mtp.MtpConstants;
import android.mtp.MtpObjectInfo;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract.Root;
import android.system.Os;
import android.system.OsConstants;
import android.provider.DocumentsContract;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;

import static com.android.mtp.MtpDatabase.strings;

@MediumTest
public class MtpDocumentsProviderTest extends AndroidTestCase {
    private final static Uri ROOTS_URI =
            DocumentsContract.buildRootsUri(MtpDocumentsProvider.AUTHORITY);
    private TestContentResolver mResolver;
    private MtpDocumentsProvider mProvider;
    private TestMtpManager mMtpManager;
    private final TestResources mResources = new TestResources();
    private MtpDatabase mDatabase;

    @Override
    public void setUp() throws IOException {
        mResolver = new TestContentResolver();
        mMtpManager = new TestMtpManager(getContext());
    }

    @Override
    public void tearDown() {
        mProvider.shutdown();
        MtpDatabase.deleteDatabase(getContext());
    }

    public void testOpenAndCloseDevice() throws Exception {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        mMtpManager.addValidDevice(new MtpDeviceRecord(
                0,
                "Device A",
                false /* unopened */,
                new MtpRoot[] {
                    new MtpRoot(
                            0 /* deviceId */,
                            1 /* storageId */,
                            "Storage A" /* volume description */,
                            1024 /* free space */,
                            2048 /* total space */,
                            "" /* no volume identifier */)
                },
                null,
                null));

        mProvider.resumeRootScanner();
        mResolver.waitForNotification(ROOTS_URI, 1);

        mProvider.openDevice(0);
        mResolver.waitForNotification(ROOTS_URI, 2);

        mProvider.closeDevice(0);
        mResolver.waitForNotification(ROOTS_URI, 3);
    }

    public void testOpenAndCloseErrorDevice() throws Exception {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        try {
            mProvider.openDevice(1);
            fail();
        } catch (Throwable error) {
            assertTrue(error instanceof IOException);
        }
        assertEquals(0, mProvider.getOpenedDeviceIds().length);

        // Check if the following notification is the first one or not.
        mMtpManager.addValidDevice(new MtpDeviceRecord(
                0,
                "Device A",
                false /* unopened */,
                new MtpRoot[] {
                    new MtpRoot(
                            0 /* deviceId */,
                            1 /* storageId */,
                            "Storage A" /* volume description */,
                            1024 /* free space */,
                            2048 /* total space */,
                            "" /* no volume identifier */)
                },
                null,
                null));
        mProvider.resumeRootScanner();
        mResolver.waitForNotification(ROOTS_URI, 1);
        mProvider.openDevice(0);
        mResolver.waitForNotification(ROOTS_URI, 2);
    }

    public void testOpenDeviceOnDemand() throws Exception {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        mMtpManager.addValidDevice(new MtpDeviceRecord(
                0,
                "Device A",
                false /* unopened */,
                new MtpRoot[] {
                    new MtpRoot(
                            0 /* deviceId */,
                            1 /* storageId */,
                            "Storage A" /* volume description */,
                            1024 /* free space */,
                            2048 /* total space */,
                            "" /* no volume identifier */)
                },
                null,
                null));
        mMtpManager.setObjectHandles(0, 1, -1, new int[0]);
        mProvider.resumeRootScanner();
        mResolver.waitForNotification(ROOTS_URI, 1);
        final String[] columns = new String[] {
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID
        };
        try (final Cursor cursor = mProvider.queryRoots(columns)) {
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToNext());
            assertEquals("Device A", cursor.getString(0));
            assertEquals(1, cursor.getLong(1));
        }
        {
            final int [] openedDevice = mProvider.getOpenedDeviceIds();
            assertEquals(0, openedDevice.length);
        }
        // Device is opened automatically when querying its children.
        try (final Cursor cursor = mProvider.queryChildDocuments("1", null, null)) {}

        {
            final int [] openedDevice = mProvider.getOpenedDeviceIds();
            assertEquals(1, openedDevice.length);
            assertEquals(0, openedDevice[0]);
        }
    }

    public void testQueryRoots() throws Exception {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        mMtpManager.addValidDevice(new MtpDeviceRecord(
                0,
                "Device A",
                false /* unopened */,
                new MtpRoot[] {
                        new MtpRoot(
                                0 /* deviceId */,
                                1 /* storageId */,
                                "Storage A" /* volume description */,
                                1024 /* free space */,
                                2048 /* total space */,
                                "" /* no volume identifier */)
                },
                null,
                null));
        mMtpManager.addValidDevice(new MtpDeviceRecord(
                1,
                "Device B",
                false /* unopened */,
                new MtpRoot[] {
                    new MtpRoot(
                            1 /* deviceId */,
                            1 /* storageId */,
                            "Storage B" /* volume description */,
                            2048 /* free space */,
                            4096 /* total space */,
                            "Identifier B" /* no volume identifier */)
                },
                null,
                null));

        {
            mProvider.openDevice(0);
            mResolver.waitForNotification(ROOTS_URI, 1);
            final Cursor cursor = mProvider.queryRoots(null);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            assertEquals("1", cursor.getString(0));
            assertEquals(Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE, cursor.getInt(1));
            assertEquals(R.drawable.ic_root_mtp, cursor.getInt(2));
            assertEquals("Device A Storage A", cursor.getString(3));
            assertEquals("1", cursor.getString(4));
            assertEquals(1024, cursor.getInt(5));
        }

        {
            mProvider.openDevice(1);
            mResolver.waitForNotification(ROOTS_URI, 2);
            final Cursor cursor = mProvider.queryRoots(null);
            assertEquals(2, cursor.getCount());
            cursor.moveToNext();
            cursor.moveToNext();
            assertEquals("2", cursor.getString(0));
            assertEquals(Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE, cursor.getInt(1));
            assertEquals(R.drawable.ic_root_mtp, cursor.getInt(2));
            assertEquals("Device B Storage B", cursor.getString(3));
            assertEquals("2", cursor.getString(4));
            assertEquals(2048, cursor.getInt(5));
        }
    }

    public void testQueryRoots_error() throws Exception {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        mMtpManager.addValidDevice(new MtpDeviceRecord(
                0, "Device A", false /* unopened */, new MtpRoot[0], null, null));
        mMtpManager.addValidDevice(new MtpDeviceRecord(
                1,
                "Device B",
                false /* unopened */,
                new MtpRoot[] {
                    new MtpRoot(
                            1 /* deviceId */,
                            1 /* storageId */,
                            "Storage B" /* volume description */,
                            2048 /* free space */,
                            4096 /* total space */,
                            "Identifier B" /* no volume identifier */)
                },
                null,
                null));
        {
            mProvider.openDevice(0);
            mProvider.resumeRootScanner();
            mResolver.waitForNotification(ROOTS_URI, 1);

            mProvider.openDevice(1);
            mProvider.resumeRootScanner();
            mResolver.waitForNotification(ROOTS_URI, 2);

            final Cursor cursor = mProvider.queryRoots(null);
            assertEquals(2, cursor.getCount());

            cursor.moveToNext();
            assertEquals("1", cursor.getString(0));
            assertEquals(Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE, cursor.getInt(1));
            assertEquals(R.drawable.ic_root_mtp, cursor.getInt(2));
            assertEquals("Device A", cursor.getString(3));
            assertEquals("1", cursor.getString(4));
            assertEquals(0, cursor.getInt(5));

            cursor.moveToNext();
            assertEquals("2", cursor.getString(0));
            assertEquals(Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE, cursor.getInt(1));
            assertEquals(R.drawable.ic_root_mtp, cursor.getInt(2));
            assertEquals("Device B Storage B", cursor.getString(3));
            assertEquals("2", cursor.getString(4));
            assertEquals(2048, cursor.getInt(5));
        }
    }

    public void testQueryDocument() throws IOException, InterruptedException, TimeoutException {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        setupRoots(0, new MtpRoot[] { new MtpRoot(0, 0, "Storage", 1000, 1000, "") });
        setupDocuments(
                0,
                0,
                MtpManager.OBJECT_HANDLE_ROOT_CHILDREN,
                "1",
                new MtpObjectInfo[] {
                        new MtpObjectInfo.Builder()
                                .setObjectHandle(100)
                                .setFormat(MtpConstants.FORMAT_EXIF_JPEG)
                                .setName("image.jpg")
                                .setDateModified(1422716400000L)
                                .setCompressedSize(1024 * 1024 * 5)
                                .setThumbCompressedSize(50 * 1024)
                                .build()
                });

        final Cursor cursor = mProvider.queryDocument("3", null);
        assertEquals(1, cursor.getCount());

        cursor.moveToNext();

        assertEquals("3", cursor.getString(0));
        assertEquals("image/jpeg", cursor.getString(1));
        assertEquals("image.jpg", cursor.getString(2));
        assertEquals(1422716400000L, cursor.getLong(3));
        assertEquals(
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE |
                DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL,
                cursor.getInt(4));
        assertEquals(1024 * 1024 * 5, cursor.getInt(5));
    }

    public void testQueryDocument_directory()
            throws IOException, InterruptedException, TimeoutException {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        setupRoots(0, new MtpRoot[] { new MtpRoot(0, 0, "Storage", 1000, 1000, "") });
        setupDocuments(
                0,
                0,
                MtpManager.OBJECT_HANDLE_ROOT_CHILDREN,
                "1",
                new MtpObjectInfo[] {
                        new MtpObjectInfo.Builder()
                                .setObjectHandle(2)
                                .setStorageId(1)
                                .setFormat(MtpConstants.FORMAT_ASSOCIATION)
                                .setName("directory")
                                .setDateModified(1422716400000L)
                                .build()
                });

        final Cursor cursor = mProvider.queryDocument("3", null);
        assertEquals(1, cursor.getCount());

        cursor.moveToNext();
        assertEquals("3", cursor.getString(0));
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, cursor.getString(1));
        assertEquals("directory", cursor.getString(2));
        assertEquals(1422716400000L, cursor.getLong(3));
        assertEquals(
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE |
                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE,
                cursor.getInt(4));
        assertEquals(0, cursor.getInt(5));
    }

    public void testQueryDocument_forRoot()
            throws IOException, InterruptedException, TimeoutException {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        setupRoots(0, new MtpRoot[] {
                new MtpRoot(
                        0 /* deviceId */,
                        1 /* storageId */,
                        "Storage A" /* volume description */,
                        1024 /* free space */,
                        4096 /* total space */,
                        "" /* no volume identifier */)
        });
        final Cursor cursor = mProvider.queryDocument("2", null);
        assertEquals(1, cursor.getCount());

        cursor.moveToNext();
        assertEquals("2", cursor.getString(0));
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, cursor.getString(1));
        assertEquals("Storage A", cursor.getString(2));
        assertTrue(cursor.isNull(3));
        assertEquals(0, cursor.getInt(4));
        assertEquals(3072, cursor.getInt(5));
    }

    public void testQueryChildDocuments() throws Exception {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        setupRoots(0, new MtpRoot[] { new MtpRoot(0, 0, "Storage", 1000, 1000, "") });
        setupDocuments(
                0,
                0,
                MtpManager.OBJECT_HANDLE_ROOT_CHILDREN,
                "1",
                new MtpObjectInfo[] {
                        new MtpObjectInfo.Builder()
                                .setObjectHandle(100)
                                .setFormat(MtpConstants.FORMAT_EXIF_JPEG)
                                .setName("image.jpg")
                                .setCompressedSize(1024 * 1024 * 5)
                                .setThumbCompressedSize(5 * 1024)
                                .setProtectionStatus(MtpConstants.PROTECTION_STATUS_READ_ONLY)
                                .build()
                });

        final Cursor cursor = mProvider.queryChildDocuments("1", null, null);
        assertEquals(1, cursor.getCount());

        assertTrue(cursor.moveToNext());
        assertEquals("3", cursor.getString(0));
        assertEquals("image/jpeg", cursor.getString(1));
        assertEquals("image.jpg", cursor.getString(2));
        assertEquals(0, cursor.getLong(3));
        assertEquals(DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL, cursor.getInt(4));
        assertEquals(1024 * 1024 * 5, cursor.getInt(5));

        cursor.close();
    }

    public void testQueryChildDocuments_cursorError() throws Exception {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        try {
            mProvider.queryChildDocuments("1", null, null);
            fail();
        } catch (Throwable error) {
            assertTrue(error instanceof FileNotFoundException);
        }
    }

    public void testQueryChildDocuments_documentError() throws Exception {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        setupRoots(0, new MtpRoot[] { new MtpRoot(0, 0, "Storage", 1000, 1000, "") });
        mMtpManager.setObjectHandles(0, 0, -1, new int[] { 1 });
        try {
            mProvider.queryChildDocuments("1", null, null);
            fail();
        } catch (Throwable error) {
            assertTrue(error instanceof FileNotFoundException);
        }
    }

    public void testDeleteDocument() throws IOException, InterruptedException, TimeoutException {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        setupRoots(0, new MtpRoot[] {
                new MtpRoot(0, 0, "Storage", 0, 0, "")
        });
        setupDocuments(0, 0, MtpManager.OBJECT_HANDLE_ROOT_CHILDREN, "1", new MtpObjectInfo[] {
                new MtpObjectInfo.Builder()
                    .setName("test.txt")
                    .setObjectHandle(1)
                    .setParent(-1)
                    .build()
        });

        mProvider.deleteDocument("3");
        assertEquals(1, mResolver.getChangeCount(
                DocumentsContract.buildChildDocumentsUri(
                        MtpDocumentsProvider.AUTHORITY, "1")));
    }

    public void testDeleteDocument_error()
            throws IOException, InterruptedException, TimeoutException {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        setupRoots(0, new MtpRoot[] {
                new MtpRoot(0, 0, "Storage", 0, 0, "")
        });
        setupDocuments(0, 0, MtpManager.OBJECT_HANDLE_ROOT_CHILDREN, "1", new MtpObjectInfo[] {
                new MtpObjectInfo.Builder()
                    .setName("test.txt")
                    .setObjectHandle(1)
                    .setParent(-1)
                    .build()
        });
        try {
            mProvider.deleteDocument("4");
            fail();
        } catch (Throwable e) {
            assertTrue(e instanceof IOException);
        }
        assertEquals(0, mResolver.getChangeCount(
                DocumentsContract.buildChildDocumentsUri(
                        MtpDocumentsProvider.AUTHORITY, "1")));
    }

    public void testOpenDocument() throws Exception {
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        setupRoots(0, new MtpRoot[] {
                new MtpRoot(0, 0, "Storage", 0, 0, "")
        });
        final byte[] bytes = "Hello world".getBytes();
        setupDocuments(0, 0, MtpManager.OBJECT_HANDLE_ROOT_CHILDREN, "1", new MtpObjectInfo[] {
                new MtpObjectInfo.Builder()
                        .setName("test.txt")
                        .setObjectHandle(1)
                        .setCompressedSize(bytes.length)
                        .setParent(-1)
                        .build()
        });
        mMtpManager.setImportFileBytes(0, 1, bytes);
        try (final ParcelFileDescriptor fd = mProvider.openDocument("3", "r", null)) {
            final byte[] readBytes = new byte[5];
            assertEquals(6, Os.lseek(fd.getFileDescriptor(), 6, OsConstants.SEEK_SET));
            assertEquals(5, Os.read(fd.getFileDescriptor(), readBytes, 0, 5));
            assertTrue(Arrays.equals("world".getBytes(), readBytes));

            assertEquals(0, Os.lseek(fd.getFileDescriptor(), 0, OsConstants.SEEK_SET));
            assertEquals(5, Os.read(fd.getFileDescriptor(), readBytes, 0, 5));
            assertTrue(Arrays.equals("Hello".getBytes(), readBytes));
        }
    }

    public void testOpenDocument_shortBytes() throws Exception {
        mMtpManager = new TestMtpManager(getContext()) {
            @Override
            MtpObjectInfo getObjectInfo(int deviceId, int objectHandle) throws IOException {
                if (objectHandle == 1) {
                    return new MtpObjectInfo.Builder(super.getObjectInfo(deviceId, objectHandle))
                            .setObjectHandle(1).setCompressedSize(1024 * 1024).build();
                }

                return super.getObjectInfo(deviceId, objectHandle);
            }
        };
        setupProvider(MtpDatabaseConstants.FLAG_DATABASE_IN_MEMORY);
        setupRoots(0, new MtpRoot[] {
                new MtpRoot(0, 0, "Storage", 0, 0, "")
        });
        final byte[] bytes = "Hello world".getBytes();
        setupDocuments(0, 0, MtpManager.OBJECT_HANDLE_ROOT_CHILDREN, "1", new MtpObjectInfo[] {
                new MtpObjectInfo.Builder()
                        .setName("test.txt")
                        .setObjectHandle(1)
                        .setCompressedSize(bytes.length)
                        .setParent(-1)
                        .build()
        });
        mMtpManager.setImportFileBytes(0, 1, bytes);
        try (final ParcelFileDescriptor fd = mProvider.openDocument("3", "r", null)) {
            final byte[] readBytes = new byte[1024 * 1024];
            assertEquals(11, Os.read(fd.getFileDescriptor(), readBytes, 0, readBytes.length));
        }
    }

    private void setupProvider(int flag) {
        mDatabase = new MtpDatabase(getContext(), flag);
        mProvider = new MtpDocumentsProvider();
        final StorageManager storageManager = getContext().getSystemService(StorageManager.class);
        assertTrue(mProvider.onCreateForTesting(
                mResources,
                mMtpManager,
                mResolver,
                mDatabase,
                storageManager,
                new TestServiceIntentSender()));
    }

    private String[] getStrings(Cursor cursor) {
        try {
            final String[] results = new String[cursor.getCount()];
            for (int i = 0; cursor.moveToNext(); i++) {
                results[i] = cursor.getString(0);
            }
            return results;
        } finally {
            cursor.close();
        }
    }

    private String[] setupRoots(int deviceId, MtpRoot[] roots)
            throws InterruptedException, TimeoutException, IOException {
        final int changeCount = mResolver.getChangeCount(ROOTS_URI);
        mMtpManager.addValidDevice(
                new MtpDeviceRecord(deviceId, "Device", false /* unopened */, roots, null, null));
        mProvider.openDevice(deviceId);
        mResolver.waitForNotification(ROOTS_URI, changeCount + 1);
        return getStrings(mProvider.queryRoots(strings(DocumentsContract.Root.COLUMN_ROOT_ID)));
    }

    private String[] setupDocuments(
            int deviceId,
            int storageId,
            int parentHandle,
            String parentDocumentId,
            MtpObjectInfo[] objects) throws FileNotFoundException {
        final int[] handles = new int[objects.length];
        int i = 0;
        for (final MtpObjectInfo info : objects) {
            handles[i] = info.getObjectHandle();
            mMtpManager.setObjectInfo(deviceId, info);
        }
        mMtpManager.setObjectHandles(deviceId, storageId, parentHandle, handles);
        return getStrings(mProvider.queryChildDocuments(
                parentDocumentId, strings(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null));
    }
}
