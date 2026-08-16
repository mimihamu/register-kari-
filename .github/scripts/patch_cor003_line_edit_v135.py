from pathlib import Path

root = Path('.')
main = root / 'app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt'
text = main.read_text(encoding='utf-8')
old = '''                        onSave = {\n                            updateCartItem(index, it)\n                            screen = AppScreen.SALES\n                        },'''
new = '''                        onSave = { edited ->\n                            val original = cart.getOrNull(index)\n                            if (original != null && edited.quantity < original.quantity) {\n                                applyCartCorrection(\n                                    index,\n                                    original.quantity - edited.quantity,\n                                    CartCorrectionTypeV135.SELECTED_LINE,\n                                )\n                                val remainingIndex = cart.indexOfFirst { it.lineId == original.lineId }\n                                if (remainingIndex >= 0) {\n                                    updateCartItem(\n                                        remainingIndex,\n                                        edited.copy(\n                                            quantity = edited.quantity,\n                                            lineId = original.lineId,\n                                        ),\n                                    )\n                                }\n                            } else {\n                                updateCartItem(index, edited)\n                            }\n                            screen = AppScreen.SALES\n                        },'''
if text.count(old) != 1:
    raise SystemExit(f'MainActivity line-edit target count={text.count(old)}')
main.write_text(text.replace(old, new, 1), encoding='utf-8')

test = root / 'app/src/test/java/jp/co/tenposinfo/register/V135CartCorrectionTest.kt'
t = test.read_text(encoding='utf-8')
old_t = '''        assertTrue(salesScreen.contains("Text(\\\"行取消\\\""))\n        assertTrue(salesScreen.contains("訂正履歴"))\n    }'''
new_t = '''        assertTrue(salesScreen.contains("Text(\\\"行取消\\\""))\n        assertTrue(salesScreen.contains("訂正履歴"))\n\n        val registerApp = source.substringBefore("@Composable\\nprivate fun Header(")\n        assertTrue(registerApp.contains("edited.quantity < original.quantity"))\n        assertTrue(registerApp.contains("CartCorrectionTypeV135.SELECTED_LINE"))\n    }'''
if t.count(old_t) != 1:
    raise SystemExit(f'test target count={t.count(old_t)}')
test.write_text(t.replace(old_t, new_t, 1), encoding='utf-8')

(root / '.github/workflows/patch-cor003-line-edit-v135.yml').unlink(missing_ok=True)
(root / '.github/scripts/patch_cor003_line_edit_v135.py').unlink(missing_ok=True)
