package com.hello.suripu.core.speech;

import com.amazonaws.services.kms.AWSKMSClient;
import com.amazonaws.services.kms.model.DecryptRequest;
import com.amazonaws.services.kms.model.EncryptRequest;
import com.google.common.base.Optional;
import com.hello.suripu.core.speech.interfaces.Vault;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Created by ksg on 8/24/16
 */
public class KmsVault implements Vault {
    private static final Logger LOGGER = LoggerFactory.getLogger(KmsVault.class);

    private final AWSKMSClient kmsClient;
    private final String kmsKeyId;

    public KmsVault(final AWSKMSClient kmsClient, final String kmsKeyId) {
        this.kmsClient = kmsClient;
        this.kmsKeyId = kmsKeyId;
    }

    @Override
    public Optional<String> encrypt(final String plainText, final Map<String, String> encryptionContext) {
        final ByteBuffer plainTextBlob = ByteBuffer.wrap(plainText.getBytes());

        final EncryptRequest encryptRequest = new EncryptRequest()
                .withKeyId(kmsKeyId)
                .withEncryptionContext(encryptionContext)
                .withPlaintext(plainTextBlob);

        final ByteBuffer cipherText = kmsClient.encrypt(encryptRequest).getCiphertextBlob();

        // copy to String
        if (cipherText.hasArray()) {
            final String cipherTextString = new String(Base64.encodeBase64(cipherText.array()));
            return Optional.of(cipherTextString);
        }
        return Optional.absent();
    }

    @Override
    public Optional<String> decrypt(final String cipherText, final Map<String, String> encryptionContext) {
        final ByteBuffer cipherTextBlob = ByteBuffer.wrap(Base64.decodeBase64(cipherText));

        final DecryptRequest decryptRequest = new DecryptRequest()
                .withEncryptionContext(encryptionContext)
                .withCiphertextBlob(cipherTextBlob);

        final ByteBuffer plainText =  kmsClient.decrypt(decryptRequest).getPlaintext();
        if (plainText.hasArray()) {
            final String plainTextString = new String(plainText.array());
            return Optional.of(plainTextString);
        }
        return Optional.absent();
    }
}
