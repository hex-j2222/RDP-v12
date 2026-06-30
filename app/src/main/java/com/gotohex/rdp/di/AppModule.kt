package com.gotohex.rdp.di

import android.content.Context
import androidx.room.Room
import com.gotohex.rdp.data.db.ConnectionLogDao
import com.gotohex.rdp.data.db.DatabaseEncryptionMigrator
import com.gotohex.rdp.data.db.DatabaseKeyProvider
import com.gotohex.rdp.data.db.HexRdpDatabase
import com.gotohex.rdp.data.db.RdpProfileDao
import com.gotohex.rdp.data.db.MIGRATION_1_2
import com.gotohex.rdp.data.db.MIGRATION_2_3
import com.gotohex.rdp.data.db.MIGRATION_3_4
import com.gotohex.rdp.data.db.MIGRATION_4_5
import com.gotohex.rdp.data.db.MIGRATION_5_6  // BUG-3 FIX
import com.gotohex.rdp.data.db.MIGRATION_6_7  // BUG-3 FIX (acceptSelfSignedCertificate)
import com.gotohex.rdp.data.db.MIGRATION_7_8  // CRIT-R1 FIX (SSH Tunnel re-encryption)
import com.gotohex.rdp.session.SessionTabManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
// 🔴 CRITICAL FIX (build break, 2nd round): net.zetetic.database.sqlcipher.SupportFactory
// does NOT exist in the new `net.zetetic:sqlcipher-android` artifact. The equivalent
// Room/androidx.sqlite glue class in this artifact is called SupportOpenHelperFactory.
// (SupportFactory only exists in the deprecated net.sqlcipher.database package.)
// See: https://github.com/sqlcipher/sqlcipher-android (README "Room API via the
// SupportOpenHelperFactory").
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HexRdpDatabase {
        // HIGH-3 FIX: Encrypt the Room database with SQLCipher.
        //
        // Step 1: retrieve or generate the 32-byte AES passphrase.
        //         The key is stored encrypted in SharedPreferences, wrapped by
        //         an AES-256-GCM key held inside the Android Keystore.
        val passphrase = DatabaseKeyProvider.getOrCreate(context)

        // CRITICAL-1 FIX: DatabaseEncryptionMigrator.migrate() calls passphrase.fill(0)
        // after the ATTACH export to minimise key material exposure.  That zeroes the
        // *same* ByteArray we are about to hand to SupportOpenHelperFactory, so Room would open
        // the database with an all-zero key and immediately fail.
        //
        // Solution: take a defensive copy for SupportOpenHelperFactory *before* migrate() runs.
        // The copy is zeroed manually after Room.build() returns so the key never
        // outlives the construction phase.  migrate() still zeroes the original,
        // which is fine — we no longer use it after this point.
        val passphraseForRoom = passphrase.copyOf()

        // Step 2: if the existing database file is plain SQLite (first boot after
        //         this security update), encrypt it in-place before Room opens it.
        //         passphrase is zeroed inside migrate() if migration actually runs.
        DatabaseEncryptionMigrator.migrate(context, passphrase)

        // Step 3: build Room with SQLCipher's SupportOpenHelperFactory so all I/O goes
        //         through AES-256-CBC (SQLCipher 4 default).
        val db = Room.databaseBuilder(
            context,
            HexRdpDatabase::class.java,
            HexRdpDatabase.DATABASE_NAME
        )
            // 🔴 FIX: SupportOpenHelperFactory(byte[]) is the real constructor in this
            // artifact (single-arg overload delegates to (password, hook=null, enableWriteAheadLogging=false)).
            .openHelperFactory(SupportOpenHelperFactory(passphraseForRoom))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

        // CRITICAL-1 FIX: zero the Room copy now that SupportOpenHelperFactory has consumed it.
        // SupportOpenHelperFactory copies the key internally on construction, so zeroing here is safe.
        passphraseForRoom.fill(0)

        return db
    }

    @Provides
    @Singleton
    fun provideRdpProfileDao(db: HexRdpDatabase): RdpProfileDao = db.rdpProfileDao()

    @Provides
    @Singleton
    fun provideConnectionLogDao(db: HexRdpDatabase): ConnectionLogDao = db.connectionLogDao()

    @Provides
    @Singleton
    fun provideSessionTabManager(): SessionTabManager = SessionTabManager()
}
