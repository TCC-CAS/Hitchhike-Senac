package com.example.hitchhike.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [UsuarioEntity::class, CaronaEntity::class, SolicitacaoEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usuarioDao(): UsuarioDao
    abstract fun caronaDao(): CaronaDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usuarios ADD COLUMN status TEXT NOT NULL DEFAULT 'ATIVO'")
                db.execSQL("ALTER TABLE usuarios ADD COLUMN cnhValidade TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE usuarios ADD COLUMN cnhRevisaoNecessaria INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usuarios ADD COLUMN solicitacaoMotoristaStatus TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE usuarios ADD COLUMN motivoRecusaMotorista TEXT DEFAULT NULL")
            }
        }

        /**
         * Remove definitivamente hashes legados de senha (SHA-256 sem salt) que possam ter sido
         * gravados em versões anteriores no Room. A autenticação dos estudantes ocorre
         * exclusivamente pelo Firebase Authentication.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE usuarios SET senhaHash = '' WHERE senhaHash != ''")
            }
        }

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hitchhike.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
