package com.framenest.data.server

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate1To2_preservesExistingRows_andCreatesListenTables() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO `servers` (
                    `id`, `name`, `host`, `port`, `username`, `domain`,
                    `credentialAlias`, `defaultShare`, `createdAtMs`, `updatedAtMs`
                ) VALUES (
                    'server-1', 'NAS', '192.0.2.1', 445, 'tester', NULL,
                    'credential-ref', 'media', 100, 200
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `playback_history` (
                    `server_id`, `share`, `path`, `display_name`, `position_ms`,
                    `duration_ms`, `completed`, `updated_at_epoch_ms`
                ) VALUES (
                    'server-1', 'media', 'movie.mkv', 'Movie', 12000,
                    60000, 0, 300
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        )
        migrated.query("SELECT `name`, `credentialAlias` FROM `servers`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("NAS", cursor.getString(0))
            assertEquals("credential-ref", cursor.getString(1))
        }
        migrated.query(
            "SELECT `position_ms`, `duration_ms` FROM `playback_history`",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(12_000L, cursor.getLong(0))
            assertEquals(60_000L, cursor.getLong(1))
        }
        migrated.execSQL(
            """
            INSERT INTO `listen_translate_job` (
                `server_id`, `share`, `path`, `source_lang`, `target_lang`,
                `content_key`, `status`, `duration_ms`, `covered_until_ms`,
                `asr_model`, `mt_model`, `updated_at_epoch_ms`, `last_error`
            ) VALUES (
                'server-1', 'media', 'movie.mkv', 'en', 'zh',
                'content', 'Partial', 60000, 3000,
                'asr', 'mt', 400, ''
            )
            """.trimIndent(),
        )
        migrated.execSQL(
            """
            INSERT INTO `listen_translate_cue` (
                `server_id`, `share`, `path`, `source_lang`, `target_lang`,
                `start_ms`, `end_ms`, `text_src`, `text_tgt`, `rev`
            ) VALUES (
                'server-1', 'media', 'movie.mkv', 'en', 'zh',
                0, 3000, 'hello', '你好', 1
            )
            """.trimIndent(),
        )
        migrated.query("SELECT COUNT(*) FROM `listen_translate_cue`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        migrated.close()
    }

    private companion object {
        const val TEST_DB = "framenest-migration-test"
    }
}
