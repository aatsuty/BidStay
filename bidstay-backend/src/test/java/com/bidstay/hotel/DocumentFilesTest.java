package com.bidstay.hotel;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DocumentFilesTest {
    @Test
    void initializesStorageWithAConfiguredBucket() {
        DocumentFiles files = new DocumentFiles(
                new MockEnvironment(), "bidstay-test-bucket", "ap-south-1", ".bidstay-documents");
        files.close();
    }
}
