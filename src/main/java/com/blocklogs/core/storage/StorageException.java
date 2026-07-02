package com.blocklogs.core.storage;

/** Thrown by {@link Database} operations when the underlying store fails. */
public class StorageException extends Exception {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
