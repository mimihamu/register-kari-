from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one audit patch anchor, found {count}")
    p.write_text(text.replace(old, new, 1))

replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/RestoreTerminalMigrationV136.kt",
    '''    fun displaySummary(): String = when (mode) {
        RestoreTerminalModeV136.SAME_TERMINAL ->
            "同一端末復旧 / terminalId=$targetTerminalId / generation=$targetGeneration / 採番下限=$saleSequenceFloor"
        RestoreTerminalModeV136.SPARE_TERMINAL ->
            "予備端末移行 / 新terminalId=$targetTerminalId / generation=$targetGeneration / 採番下限=$saleSequenceFloor"
    }
''',
    '''    fun displaySummary(): String = when (mode) {
        RestoreTerminalModeV136.SAME_TERMINAL ->
            "同一端末復旧 / storeId=$storeId / oldTerminalId=$sourceTerminalId / newTerminalId=$targetTerminalId / " +
                "sourceGeneration=$sourceGeneration / generation=$targetGeneration / 採番下限=$saleSequenceFloor / " +
                "確認最大番号=$remoteAckMaxSaleId"
        RestoreTerminalModeV136.SPARE_TERMINAL ->
            "予備端末移行 / storeId=$storeId / oldTerminalId=$sourceTerminalId / newTerminalId=$targetTerminalId / " +
                "sourceGeneration=$sourceGeneration / generation=$targetGeneration / 採番下限=$saleSequenceFloor / " +
                "確認最大番号=$remoteAckMaxSaleId"
    }
''',
)

replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/DataRestoreBootstrapV086.kt",
    '''                    " / BKP-005=${plan["restore_mode"].orEmpty()}" +
                    " / terminalId=${plan["target_terminal_id"].orEmpty()}" +
                    " / generation=${plan["target_generation"].orEmpty()}" +
                    " / sale-floor=${plan["sale_sequence_floor"].orEmpty()}",
''',
    '''                    " / BKP-005=${plan["restore_mode"].orEmpty()}" +
                    " / storeId=${plan["target_store_id"].orEmpty()}" +
                    " / oldTerminalId=${plan["source_terminal_id"].orEmpty()}" +
                    " / newTerminalId=${plan["target_terminal_id"].orEmpty()}" +
                    " / source-generation=${plan["source_generation"].orEmpty()}" +
                    " / generation=${plan["target_generation"].orEmpty()}" +
                    " / sale-floor=${plan["sale_sequence_floor"].orEmpty()}" +
                    " / confirmed-max=${plan["remote_ack_max_sale_id"].orEmpty()}",
''',
)

replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/DataRestoreBootstrapV086.kt",
    '''                    put("detail", "${plan["backup_file"].orEmpty()} / 起動時復元 / v1.16 WAL・migration-safe rollback / BKP-005=${plan["restore_mode"].orEmpty()} / terminalId=${plan["target_terminal_id"].orEmpty()} / generation=${plan["target_generation"].orEmpty()} / sale-floor=${plan["sale_sequence_floor"].orEmpty()}")
''',
    '''                    put(
                        "detail",
                        "${plan["backup_file"].orEmpty()} / 起動時復元 / v1.16 WAL・migration-safe rollback / " +
                            "BKP-005=${plan["restore_mode"].orEmpty()} / storeId=${plan["target_store_id"].orEmpty()} / " +
                            "oldTerminalId=${plan["source_terminal_id"].orEmpty()} / " +
                            "newTerminalId=${plan["target_terminal_id"].orEmpty()} / " +
                            "source-generation=${plan["source_generation"].orEmpty()} / " +
                            "generation=${plan["target_generation"].orEmpty()} / " +
                            "sale-floor=${plan["sale_sequence_floor"].orEmpty()} / " +
                            "confirmed-max=${plan["remote_ack_max_sale_id"].orEmpty()}",
                    )
''',
)

replace_once(
    "app/src/test/java/jp/co/tenposinfo/register/V143Bkp005TerminalMigrationTest.kt",
    '''        val bootstrap = source("DataRestoreBootstrapV086.kt")
''',
    '''        val bootstrap = source("DataRestoreBootstrapV086.kt")
''',
)

# Add explicit audit-contract assertions after the existing sqlite_sequence assertion.
replace_once(
    "app/src/test/java/jp/co/tenposinfo/register/V143Bkp005TerminalMigrationTest.kt",
    '''        assertTrue(helper.contains("sqlite_sequence"))
        assertTrue(activity.contains("同一端末復旧"))
''',
    '''        assertTrue(helper.contains("sqlite_sequence"))
        assertTrue(helper.contains("oldTerminalId=$sourceTerminalId"))
        assertTrue(helper.contains("newTerminalId=$targetTerminalId"))
        assertTrue(helper.contains("確認最大番号=$remoteAckMaxSaleId"))
        assertTrue(bootstrap.contains("source_terminal_id"))
        assertTrue(bootstrap.contains("target_store_id"))
        assertTrue(bootstrap.contains("remote_ack_max_sale_id"))
        assertTrue(activity.contains("同一端末復旧"))
''',
)

print("BKP-005 audit details patch applied")
