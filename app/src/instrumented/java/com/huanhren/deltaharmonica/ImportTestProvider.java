package com.huanhren.deltaharmonica;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Available only in the isolated instrumentation host. */
public final class ImportTestProvider extends ContentProvider {
    private byte[] payload = new byte[0];
    private String name = "test.json";
    private CountDownLatch gate = new CountDownLatch(0);
    private static final String AUTHORITY = "com.huanhren.deltaharmonica.instrumented.importprovider";
    @Override public boolean onCreate() {
        if (!"com.huanhren.deltaharmonica.instrumented".equals(getContext().getPackageName())) {
            throw new SecurityException("Non-isolated test provider");
        }
        return true;
    }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        if ("configure".equals(method)) {
            payload = extras.getByteArray("bytes"); name = extras.getString("name"); gate = new CountDownLatch(1);
        } else if ("release".equals(method)) gate.countDown();
        else throw new IllegalArgumentException("Unknown test provider operation");
        return new Bundle();
    }
    @Override public synchronized Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!AUTHORITY.equals(uri.getAuthority())) throw new SecurityException("Unexpected test provider authority");
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME}); cursor.addRow(new Object[]{name}); return cursor;
    }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!AUTHORITY.equals(uri.getAuthority()) || !"r".equals(mode)) throw new SecurityException("Unexpected test provider access");
        String path = uri.getLastPathSegment();
        if ("denied".equals(path)) throw new SecurityException("URI access revoked");
        if ("missing".equals(path)) throw new FileNotFoundException("file missing");
        if ("io".equals(path)) throw new FileNotFoundException("provider read failure");
        if (!"normal".equals(path)) throw new FileNotFoundException("Unknown test fixture");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            final byte[] content; final CountDownLatch latch;
            synchronized (this) { content = payload; latch = gate; }
            new Thread(() -> {
                try {
                    if (!latch.await(120, TimeUnit.SECONDS)) throw new IOException("test provider timed out");
                    try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { out.write(content); }
                } catch (Exception error) { try { pipe[1].closeWithError(error.toString()); } catch (Exception ignored) {} }
            }).start();
            return pipe[0];
        } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
