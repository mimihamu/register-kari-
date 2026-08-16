from pathlib import Path
p = Path('app/src/main/java/jp/co/tenposinfo/register/OperationsHubActivityV030.kt')
s = p.read_text(encoding='utf-8')
old = 'ManualReturnAccountingV135.apply(context.applicationContext, store.dailySummary())'
new = 'store.dailySummary()'
if s.count(old) != 1:
    raise SystemExit(f'expected one manual return hub accounting wrapper, found {s.count(old)}')
p.write_text(s.replace(old, new), encoding='utf-8')
print('removed duplicate manual return accounting wrapper from operations hub')
