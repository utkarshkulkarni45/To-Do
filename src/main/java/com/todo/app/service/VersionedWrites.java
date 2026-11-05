package com.todo.app.service;

import com.todo.app.exception.ApiException;
import com.todo.app.exception.ErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * A client that read a record sends its version back with the write. If the stored version has moved
 * on, another write landed in between and the newer one must not be overwritten by the older form.
 */
final class VersionedWrites {

    private VersionedWrites() {
    }

    static void requireUnchanged(String what, long storedVersion, Long versionReadByClient) {
        if (versionReadByClient != null && versionReadByClient != storedVersion) {
            throw conflict(what);
        }
    }

    static ApiException conflict(String what) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VERSION_CONFLICT,
                "This " + what + " changed since it was loaded. Reload it and try again.");
    }
}
