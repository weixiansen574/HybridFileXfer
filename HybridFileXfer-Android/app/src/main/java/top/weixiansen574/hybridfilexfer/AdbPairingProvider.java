package top.weixiansen574.hybridfilexfer;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.util.ArrayList;

/**
 * Read-only handoff to an already authorized ADB shell. It never starts a server,
 * approves a start request, changes pairing codes, or stores the code on disk.
 */
public final class AdbPairingProvider extends ContentProvider {
    @Override public boolean onCreate() {
        // Provider creation must not construct ServerSession or bind an IO service.
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        AdbPairingState.requireCaller(uid);
        if (!"current".equals(method)) throw new IllegalArgumentException("Unknown pairing operation");
        Bundle result = new Bundle();
        result.putString("hfx_pairing", ServerSession.adbPairingPayload(uid));
        return result;
    }

    private static UnsupportedOperationException unsupported() {
        AdbPairingState.requireCaller(Binder.getCallingUid());
        return new UnsupportedOperationException("Only the current pairing lookup is supported");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        throw unsupported();
    }

    @Override public String getType(Uri uri) {
        throw unsupported();
    }

    @Override public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
        throw unsupported();
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw unsupported();
    }

    @Override public int bulkInsert(Uri uri, ContentValues[] values) {
        throw unsupported();
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw unsupported();
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw unsupported();
    }

    @Override public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations) {
        throw unsupported();
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        throw unsupported();
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        throw unsupported();
    }

    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts)
            throws FileNotFoundException {
        throw unsupported();
    }

    @Override public Uri canonicalize(Uri uri) {
        throw unsupported();
    }

    @Override public Uri uncanonicalize(Uri uri) {
        throw unsupported();
    }

    @Override public boolean refresh(Uri uri, Bundle args, CancellationSignal cancellationSignal) {
        throw unsupported();
    }
}
