from pathlib import Path
import subprocess

path = Path('app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt')
text = path.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str):
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected 1 occurrence, found {count}')
    text = text.replace(old, new, 1)

replace_once(
    '    val store = remember { SaleReceiptReprintOperationsStore(appContext) }\n',
    '    val store = remember { SaleReceiptReprintStablePagingStore(appContext) }\n',
    'route store',
)
replace_once(
    '    store: SaleReceiptReprintOperationsStore,\n',
    '    store: SaleReceiptReprintStablePagingStore,\n',
    'screen store type',
)
replace_once(
    '''    var appliedCriteria by remember { mutableStateOf(SaleReceiptReprintLedgerCriteria()) }
    var pageOffset by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<Long?>(null) }

    val page = remember(appliedCriteria, pageOffset, refreshEpoch) {
        store.search(appliedCriteria, pageOffset)
    }
    val summary = remember(refreshEpoch) { store.summary() }
    val entries = page.entries
    val selected = entries.firstOrNull { it.auditId == selectedId }
        ?: entries.firstOrNull()

    LaunchedEffect(page.totalMatches, pageOffset, entries.size) {
        if (pageOffset > 0 && entries.isEmpty()) {
            pageOffset = if (page.totalMatches <= 0) {
                0
            } else {
                ((page.totalMatches - 1) / page.pageSize) * page.pageSize
            }
            selectedId = null
        }
    }
''',
    '''    var appliedCriteria by remember { mutableStateOf(SaleReceiptReprintLedgerCriteria()) }
    var snapshot by remember { mutableStateOf(store.captureSnapshot(appliedCriteria)) }
    var pageCursor by remember { mutableStateOf<SaleReceiptReprintLedgerCursor?>(null) }
    var cursorHistory by remember { mutableStateOf<List<SaleReceiptReprintLedgerCursor?>>(emptyList()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }

    fun applyCriteria(criteria: SaleReceiptReprintLedgerCriteria) {
        appliedCriteria = criteria
        snapshot = store.captureSnapshot(criteria)
        pageCursor = null
        cursorHistory = emptyList()
        selectedId = null
    }

    val page = remember(appliedCriteria, snapshot, pageCursor, refreshEpoch) {
        store.searchStable(appliedCriteria, snapshot, pageCursor)
    }
    val summary = remember(refreshEpoch) { store.summary() }
    val entries = page.entries
    val selected = entries.firstOrNull { it.auditId == selectedId }
        ?: entries.firstOrNull()
''',
    'state and page model',
)
replace_once(
    '            Text("SQLite直接検索 / 期間DB絞込 / 1ページ${SaleReceiptReprintLedgerPolicy.DATABASE_PAGE_SIZE}件", color = Color.White)\n',
    '            Text("SQLite直接検索 / 検索時点固定 / keyset ${SaleReceiptReprintLedgerPolicy.DATABASE_PAGE_SIZE}件", color = Color.White)\n',
    'header stable paging label',
)
replace_once(
    '''                            appliedCriteria = SaleReceiptReprintLedgerCriteria(
                                filter = filter,
                                period = SaleReceiptReprintLedgerPeriod.CUSTOM,
                                customStartInclusive = range.startInclusive,
                                customEndExclusive = range.endExclusive,
                                query = query,
                            )
                            dateError = null
                            pageOffset = 0
                            selectedId = null
''',
    '''                            applyCriteria(
                                SaleReceiptReprintLedgerCriteria(
                                    filter = filter,
                                    period = SaleReceiptReprintLedgerPeriod.CUSTOM,
                                    customStartInclusive = range.startInclusive,
                                    customEndExclusive = range.endExclusive,
                                    query = query,
                                ),
                            )
                            dateError = null
''',
    'search custom apply',
)
replace_once(
    '''                        appliedCriteria = SaleReceiptReprintLedgerCriteria(filter = filter, period = period, query = query)
                        dateError = null
                        pageOffset = 0
                        selectedId = null
''',
    '''                        applyCriteria(SaleReceiptReprintLedgerCriteria(filter = filter, period = period, query = query))
                        dateError = null
''',
    'search fixed apply',
)
replace_once(
    '''                    appliedCriteria = SaleReceiptReprintLedgerCriteria()
                    pageOffset = 0
                    selectedId = null
''',
    '''                    applyCriteria(SaleReceiptReprintLedgerCriteria())
''',
    'clear apply',
)
replace_once(
    '''                    appliedCriteria = SaleReceiptReprintLedgerCriteria(
                        filter = item,
                        period = period,
                        customStartInclusive = if (period == SaleReceiptReprintLedgerPeriod.CUSTOM) customRange?.startInclusive else null,
                        customEndExclusive = if (period == SaleReceiptReprintLedgerPeriod.CUSTOM) customRange?.endExclusive else null,
                        query = query,
                    )
                    pageOffset = 0
                    selectedId = null
''',
    '''                    applyCriteria(
                        SaleReceiptReprintLedgerCriteria(
                            filter = item,
                            period = period,
                            customStartInclusive = if (period == SaleReceiptReprintLedgerPeriod.CUSTOM) customRange?.startInclusive else null,
                            customEndExclusive = if (period == SaleReceiptReprintLedgerPeriod.CUSTOM) customRange?.endExclusive else null,
                            query = query,
                        ),
                    )
''',
    'status filter apply',
)
replace_once(
    '''            val resultFrom = if (page.totalMatches == 0 || entries.isEmpty()) 0 else page.offset + 1
            val resultTo = if (entries.isEmpty()) 0 else page.offset + entries.size
            Text("表示 $resultFrom-$resultTo / ${page.totalMatches}件", color = Color.Gray)
            OutlinedButton(
                enabled = page.offset > 0,
                onClick = {
                    pageOffset = (page.offset - page.pageSize).coerceAtLeast(0)
                    selectedId = null
                },
            ) { Text("前へ") }
            OutlinedButton(
                enabled = page.hasNext,
                onClick = {
                    pageOffset = page.offset + page.pageSize
                    selectedId = null
                },
            ) { Text("次へ") }
''',
    '''            val pageNumber = cursorHistory.size + 1
            Text("ページ $pageNumber / 条件一致 ${page.totalMatches}件", color = Color.Gray)
            if (page.newerAuditCount > 0) {
                Text(
                    "新しい再印字要求 ${page.newerAuditCount}件（検索再実行で反映）",
                    color = ReprintLedgerDanger,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
            OutlinedButton(
                enabled = cursorHistory.isNotEmpty(),
                onClick = {
                    if (cursorHistory.isNotEmpty()) {
                        pageCursor = cursorHistory.last()
                        cursorHistory = cursorHistory.dropLast(1)
                        selectedId = null
                    }
                },
            ) { Text("前へ") }
            OutlinedButton(
                enabled = page.hasNext && page.nextCursor != null,
                onClick = {
                    page.nextCursor?.let { next ->
                        cursorHistory = cursorHistory + listOf(pageCursor)
                        pageCursor = next
                        selectedId = null
                    }
                },
            ) { Text("次へ") }
''',
    'keyset previous next controls',
)
replace_once(
    '''                    appliedCriteria = SaleReceiptReprintLedgerCriteria(filter = filter, period = item, query = query)
                    pageOffset = 0
                    selectedId = null
''',
    '''                    applyCriteria(SaleReceiptReprintLedgerCriteria(filter = filter, period = item, query = query))
''',
    'fixed period apply',
)
replace_once(
    '''                    appliedCriteria = SaleReceiptReprintLedgerCriteria(
                        filter = filter,
                        period = SaleReceiptReprintLedgerPeriod.CUSTOM,
                        customStartInclusive = range.startInclusive,
                        customEndExclusive = range.endExclusive,
                        query = query,
                    )
                    dateError = null
                    pageOffset = 0
                    selectedId = null
''',
    '''                    applyCriteria(
                        SaleReceiptReprintLedgerCriteria(
                            filter = filter,
                            period = SaleReceiptReprintLedgerPeriod.CUSTOM,
                            customStartInclusive = range.startInclusive,
                            customEndExclusive = range.endExclusive,
                            query = query,
                        ),
                    )
                    dateError = null
''',
    'custom button apply',
)
replace_once(
    '            Text("期間変更時は先頭ページへ戻ります / 5秒更新は条件・ページを維持", color = Color.Gray, fontSize = 12.sp)\n',
    '            Text("条件変更時は新snapshot / 5秒更新はsnapshot・ページを維持", color = Color.Gray, fontSize = 12.sp)\n',
    'period stable note',
)

path.write_text(text, encoding='utf-8')
Path('scripts/wire_v073_stable_paging.py').unlink()

final = path.read_text(encoding='utf-8')
checks = [
    'SaleReceiptReprintStablePagingStore',
    'store.captureSnapshot(criteria)',
    'store.searchStable(appliedCriteria, snapshot, pageCursor)',
    'cursorHistory = cursorHistory + listOf(pageCursor)',
    '新しい再印字要求',
    '検索時点固定',
]
for needle in checks:
    if needle not in final:
        raise RuntimeError(f'missing {needle!r}')
if 'pageOffset' in final:
    raise RuntimeError('pageOffset remained in SCR-648 after keyset wiring')

subprocess.run(['git', 'config', 'user.name', 'tsuguregi-ci'], check=True)
subprocess.run(['git', 'config', 'user.email', 'tsuguregi-ci@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'commit', '-m', 'wire v0.73 stable keyset paging into SCR-648'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'WIRE_COMMIT={sha}')
subprocess.run(['git', 'status', '--short'], check=True)
subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.73'], check=True)
