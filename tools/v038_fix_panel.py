from pathlib import Path

path = Path('app/src/main/java/jp/co/tenposinfo/register/OutboxDeliveryOperationsPanel.kt')
text = path.read_text(encoding='utf-8')
text = text.replace('runCatching(store::recentItems)', 'runCatching { store.recentItems() }')
text = text.replace('runCatching(store::recentAudit)', 'runCatching { store.recentAudit() }')
path.write_text(text, encoding='utf-8')
print('v0.38 panel default-argument calls fixed')
