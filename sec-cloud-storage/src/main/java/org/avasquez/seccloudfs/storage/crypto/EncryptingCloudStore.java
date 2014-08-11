package org.avasquez.seccloudfs.storage.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.apache.shiro.crypto.AbstractSymmetricCipherService;
import org.apache.shiro.crypto.CryptoException;
import org.avasquez.seccloudfs.cloud.CloudStore;
import org.avasquez.seccloudfs.exception.DbException;
import org.avasquez.seccloudfs.storage.db.model.EncryptionKey;
import org.avasquez.seccloudfs.storage.db.repos.EncryptionKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;

/**
 * {@link org.avasquez.seccloudfs.cloud.CloudStore} decorator that encrypts the data before upload and decrypts it
 * after download.
 *
 * @author avasquez
 */
public class EncryptingCloudStore implements CloudStore {

    private static final Logger logger = LoggerFactory.getLogger(EncryptingCloudStore.class);

    private CloudStore underlyingStore;
    private AbstractSymmetricCipherService cipherService;
    private EncryptionKeyRepository keyRepository;

    @Required
    public void setUnderlyingStore(final CloudStore underlyingStore) {
        this.underlyingStore = underlyingStore;
    }

    @Required
    public void setCipherService(final AbstractSymmetricCipherService cipherService) {
        this.cipherService = cipherService;
    }

    @Required
    public void setKeyRepository(final EncryptionKeyRepository keyRepository) {
        this.keyRepository = keyRepository;
    }

    @Override
    public String getName() {
        return underlyingStore.getName();
    }

    @Override
    public long upload(final String id, final SeekableByteChannel src, final long length) throws IOException {
        Path tmpFile = Files.createTempFile(id, null, null);

        try (FileChannel tmpChannel = FileChannel.open(tmpFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            InputStream in = Channels.newInputStream(src);
            OutputStream out = Channels.newOutputStream(tmpChannel);
            byte[] key = cipherService.generateNewKey().getEncoded();

            encryptData(id, in, out, key);

            saveEncryptionKey(id, key);

            logger.debug("Data '{}' successfully encrypted", id);

            // Reset channel for reading
            tmpChannel.position(0);

            return underlyingStore.upload(id, tmpChannel, length);
        }
    }

    @Override
    public long download(final String id, final SeekableByteChannel target) throws IOException {
        Path tmpFile = Files.createTempFile(id, null, null);

        try (FileChannel tmpChannel = FileChannel.open(tmpFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long bytesDownloaded = underlyingStore.download(id, tmpChannel);

            // Reset channel for reading
            tmpChannel.position(0);

            InputStream in = Channels.newInputStream(tmpChannel);
            OutputStream out = Channels.newOutputStream(target);
            byte[] key = getEncryptionKey(id);

            decryptData(id, in, out, key);

            logger.debug("Data '{}' successfully decrypted", id);

            return bytesDownloaded;
        }
    }

    @Override
    public void delete(final String id) throws IOException {
        underlyingStore.delete(id);
    }

    @Override
    public long getTotalSpace() throws IOException {
        return underlyingStore.getTotalSpace();
    }

    @Override
    public long getAvailableSpace() throws IOException {
        return underlyingStore.getAvailableSpace();
    }

    private void encryptData(String dataId, InputStream in, OutputStream out, byte[] key) throws IOException {
        try {
            cipherService.encrypt(in, out, key);
        } catch (CryptoException e) {
            throw new IOException("Failed to encrypt data '" + dataId + "'", e);
        }
    }

    private void decryptData(String dataId, InputStream in, OutputStream out, byte[] key) throws IOException {
        try {
            cipherService.decrypt(in, out, key);
        } catch (CryptoException e) {
            throw new IOException("Failed to decrypt data '" + dataId + "'", e);
        }
    }

    private byte[] getEncryptionKey(String dataId) throws IOException {
        try {
            EncryptionKey key = keyRepository.findByDataId(dataId);
            if (key != null) {
                return key.getKey();
            } else {
                throw new IOException("No encryption key found for data ID '" + dataId + "'");
            }
        } catch (DbException e) {
            throw new IOException("Unable to retrieve encryption key for data '" + dataId + "' in repository", e);
        }
    }

    private void saveEncryptionKey(String dataId, byte[] key) throws IOException {
        try {
            keyRepository.save(new EncryptionKey(dataId, key));
        } catch (DbException e) {
            throw new IOException("Unable to save encryption key for data '" + dataId + "' in repository", e);
        }
    }

}
