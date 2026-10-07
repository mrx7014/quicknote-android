package com.quicknote.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidationTest {
    @Test fun acceptsExpectedBackupPaths() {
        assertTrue(BackupValidation.isSafeZipName("manifest.json"))
        assertTrue(BackupValidation.isSafeZipName("media/12.m4a"))
        assertTrue(BackupValidation.isSafeZipName("media/"))
        assertTrue(BackupValidation.isSafeMediaName("media/12.m4a"))
    }

    @Test fun rejectsTraversalAndPlatformSpecificPaths() {
        listOf("../secret", "media/../secret", "/absolute", "C:/drive", "media\\voice.m4a", "media//nested", "media/./file").forEach {
            assertFalse("unsafe path accepted: $it", BackupValidation.isSafeZipName(it))
        }
        listOf("media/..", "media/.", "media/../secret", "media/sub/voice.m4a", "media/a/b.m4a", "media/").forEach {
            assertFalse("unsafe media path accepted: $it", BackupValidation.isSafeMediaName(it))
        }
    }
}
