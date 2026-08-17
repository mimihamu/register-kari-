from pathlib import Path
p = Path('app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt')
s = p.read_text(encoding='utf-8')
old = '    val itemCount: Int,\n    val guestCount: Int = 0,\n    val totalAmount: Long,\n)'
new = '    val itemCount: Int,\n    val totalAmount: Long,\n    val guestCount: Int = 0,\n)'
if s.count(old) != 1:
    raise SystemExit(f'expected one HeldTicket model match, got {s.count(old)}')
p.write_text(s.replace(old, new, 1), encoding='utf-8')
