package jp.co.tenposinfo.register

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

enum class ReceiptStampDitherV136(val displayName: String) { THRESHOLD("閾値"), BAYER_4X4("Bayer 4x4"), FLOYD_STEINBERG("Floyd-Steinberg") }

data class ReceiptStampSettingsV136(
    val enabled: Boolean = false,
    val brightness: Int = 0,
    val threshold: Int = 128,
    val dither: ReceiptStampDitherV136 = ReceiptStampDitherV136.THRESHOLD,
    val rotationDegrees: Int = 0,
    val cropPercent: Int = 0,
    val stampVersion: Long = 0L,
    val sourceName: String = "",
    val receiptPlacement: ReceiptStampPlacementV136 = ReceiptStampPlacementV136.TOP,
    val receiptDocumentType: ReceiptStampDocumentTypeV136 = ReceiptStampDocumentTypeV136.RECEIPT,
)

data class ArgbImageV136(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(width > 0 && height > 0); require(pixels.size == width * height) }
}

data class MonochromeRasterV136(val widthDots: Int, val heightDots: Int, val bytesPerRow: Int, val data: ByteArray) {
    init { require(widthDots > 0 && heightDots > 0); require(bytesPerRow == (widthDots + 7) / 8); require(data.size == bytesPerRow * heightDots) }
    fun isBlack(x: Int, y: Int): Boolean { require(x in 0 until widthDots && y in 0 until heightDots); val value = data[y * bytesPerRow + x / 8].toInt() and 0xFF; return (value and (0x80 ushr (x % 8))) != 0 }
}

object ReceiptStampPolicyV136 {
    const val MAX_SOURCE_BYTES = 2 * 1024 * 1024; const val MAX_SOURCE_DIMENSION = 2_000
    const val MIN_BRIGHTNESS = -100; const val MAX_BRIGHTNESS = 100; const val DEFAULT_BRIGHTNESS = 0
    const val MIN_THRESHOLD = 0; const val MAX_THRESHOLD = 255; const val DEFAULT_THRESHOLD = 128
    const val MM58_MAX_DOTS = 384; const val MM80_MAX_DOTS = 576; const val MAX_CROP_PERCENT = 40
    fun maxWidthDots(paper: ReceiptPaper): Int = if (paper == ReceiptPaper.MM58) MM58_MAX_DOTS else MM80_MAX_DOTS
    fun normalize(settings: ReceiptStampSettingsV136) = settings.copy(brightness=settings.brightness.coerceIn(MIN_BRIGHTNESS,MAX_BRIGHTNESS), threshold=settings.threshold.coerceIn(MIN_THRESHOLD,MAX_THRESHOLD), rotationDegrees=normalizeRotation(settings.rotationDegrees), cropPercent=settings.cropPercent.coerceIn(0,MAX_CROP_PERCENT), sourceName=settings.sourceName.trim().take(120))
    fun normalizeRotation(degrees:Int):Int { val n=((degrees%360)+360)%360; return if(n in setOf(0,90,180,270)) n else 0 }
    fun validateSource(byteCount:Int,width:Int,height:Int){ require(byteCount in 1..MAX_SOURCE_BYTES){"画像は2MB以下のPNG/JPEGを選択してください"}; require(width in 1..MAX_SOURCE_DIMENSION && height in 1..MAX_SOURCE_DIMENSION){"画像サイズは2000×2000px以下にしてください"} }
    fun fitDimensions(width:Int,height:Int,paper:ReceiptPaper):Pair<Int,Int>{ require(width>0&&height>0); val max=maxWidthDots(paper); if(width<=max)return width to height; val scale=max.toDouble()/width; return max to (height*scale).roundToInt().coerceAtLeast(1) }
}

object ReceiptStampTransformV136 {
    fun apply(image:ArgbImageV136,settings:ReceiptStampSettingsV136):ArgbImageV136 { val n=ReceiptStampPolicyV136.normalize(settings); val cx=(image.width*n.cropPercent/100.0).roundToInt().coerceIn(0,(image.width-1)/2); val cy=(image.height*n.cropPercent/100.0).roundToInt().coerceIn(0,(image.height-1)/2); val w=image.width-cx*2; val h=image.height-cy*2; val p=IntArray(w*h); for(y in 0 until h){val s=(y+cy)*image.width+cx; image.pixels.copyInto(p,y*w,s,s+w)}; return rotate(ArgbImageV136(w,h,p),n.rotationDegrees) }
    private fun rotate(s:ArgbImageV136,d:Int):ArgbImageV136=when(d){0->s;90->{val w=s.height;val h=s.width;val o=IntArray(w*h);for(y in 0 until s.height)for(x in 0 until s.width)o[x*w+s.height-1-y]=s.pixels[y*s.width+x];ArgbImageV136(w,h,o)};180->{val o=IntArray(s.pixels.size);for(i in s.pixels.indices)o[s.pixels.lastIndex-i]=s.pixels[i];ArgbImageV136(s.width,s.height,o)};270->{val w=s.height;val h=s.width;val o=IntArray(w*h);for(y in 0 until s.height)for(x in 0 until s.width)o[(s.width-1-x)*w+y]=s.pixels[y*s.width+x];ArgbImageV136(w,h,o)};else->error("unsupported rotation: $d")}
}

object ReceiptStampRasterizerV136 {
    private val bayer4=intArrayOf(0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5)
    fun rasterize(image:ArgbImageV136,settings:ReceiptStampSettingsV136,paper:ReceiptPaper):MonochromeRasterV136{val n=ReceiptStampPolicyV136.normalize(settings);val t=ReceiptStampTransformV136.apply(image,n);val (w,h)=ReceiptStampPolicyV136.fitDimensions(t.width,t.height,paper);val px=if(w==t.width&&h==t.height)t.pixels.copyOf() else scaleNearest(t,w,h);val lum=DoubleArray(px.size){adjustedLuminance(px[it],n.brightness)};val black=when(n.dither){ReceiptStampDitherV136.THRESHOLD->BooleanArray(lum.size){lum[it]<n.threshold};ReceiptStampDitherV136.BAYER_4X4->BooleanArray(lum.size){i->val x=i%w;val y=i/w;lum[i]<(n.threshold+(bayer4[(y%4)*4+x%4]-7.5)*8).coerceIn(0.0,255.0)};ReceiptStampDitherV136.FLOYD_STEINBERG->floyd(lum,w,h,n.threshold)};return pack(black,w,h)}
    fun adjustedLuminance(argb:Int,brightness:Int):Double{val a=argb ushr 24 and 255;val r=argb ushr 16 and 255;val g=argb ushr 8 and 255;val b=argb and 255;val rr=(r*a+255*(255-a))/255.0;val gg=(g*a+255*(255-a))/255.0;val bb=(b*a+255*(255-a))/255.0;return ((299*rr+587*gg+114*bb)/1000+brightness.coerceIn(-100,100)*255.0/100).coerceIn(0.0,255.0)}
    private fun scaleNearest(i:ArgbImageV136,w:Int,h:Int)=IntArray(w*h){n->val y=n/w;val x=n%w;val sy=((y.toLong()*i.height)/h).toInt().coerceAtMost(i.height-1);val sx=((x.toLong()*i.width)/w).toInt().coerceAtMost(i.width-1);i.pixels[sy*i.width+sx]}
    private fun floyd(input:DoubleArray,w:Int,h:Int,threshold:Int):BooleanArray{val v=input.copyOf();val black=BooleanArray(v.size);fun add(x:Int,y:Int,e:Double,k:Double){if(x in 0 until w&&y in 0 until h){val i=y*w+x;v[i]=(v[i]+e*k).coerceIn(0.0,255.0)}};for(y in 0 until h)for(x in 0 until w){val i=y*w+x;val old=v[i];val q=old<threshold;black[i]=q;val e=old-if(q)0.0 else 255.0;add(x+1,y,e,7.0/16);add(x-1,y+1,e,3.0/16);add(x,y+1,e,5.0/16);add(x+1,y+1,e,1.0/16)};return black}
    private fun pack(black:BooleanArray,w:Int,h:Int):MonochromeRasterV136{val bpr=(w+7)/8;val data=ByteArray(bpr*h);for(y in 0 until h)for(x in 0 until w)if(black[y*w+x]){val i=y*bpr+x/8;data[i]=(data[i].toInt() or (0x80 ushr(x%8))).toByte()};return MonochromeRasterV136(w,h,bpr,data)}
}

object ReceiptStampEscPosV136 { fun encodeRaster(r:MonochromeRasterV136):ByteArray{val x=r.bytesPerRow;val y=r.heightDots;return byteArrayOf(0x1B,0x40,0x1B,0x61,0x01,0x1D,0x76,0x30,0x00,(x and 255).toByte(),((x ushr 8)and 255).toByte(),(y and 255).toByte(),((y ushr 8)and 255).toByte())+r.data+byteArrayOf(0x0A,0x1B,0x61,0x00)} }

class ReceiptStampSettingsStoreV136(context:Context){
    private val applicationContext=context.applicationContext; private val preferences=applicationContext.getSharedPreferences("receipt_stamp_settings_v136",Context.MODE_PRIVATE); private val sourceFile=File(applicationContext.filesDir,"receipt_stamp_v136/source_image.bin")
    fun load():ReceiptStampSettingsV136{val stored=ReceiptStampSettingsV136(enabled=preferences.getBoolean("enabled",false),brightness=preferences.getInt("brightness",0),threshold=preferences.getInt("threshold",128),dither=runCatching{ReceiptStampDitherV136.valueOf(preferences.getString("dither",ReceiptStampDitherV136.THRESHOLD.name).orEmpty())}.getOrDefault(ReceiptStampDitherV136.THRESHOLD),rotationDegrees=preferences.getInt("rotation_degrees",0),cropPercent=preferences.getInt("crop_percent",0),stampVersion=preferences.getLong("stamp_version",0).coerceAtLeast(0),sourceName=preferences.getString("source_name","").orEmpty(),receiptPlacement=runCatching{ReceiptStampPlacementV136.valueOf(preferences.getString("receipt_placement",ReceiptStampPlacementV136.TOP.name).orEmpty())}.getOrDefault(ReceiptStampPlacementV136.TOP),receiptDocumentType=runCatching{ReceiptStampDocumentTypeV136.valueOf(preferences.getString("receipt_document_type",ReceiptStampDocumentTypeV136.RECEIPT.name).orEmpty())}.getOrDefault(ReceiptStampDocumentTypeV136.RECEIPT));return ReceiptStampPolicyV136.normalize(if(sourceFile.isFile)stored else stored.copy(enabled=false,sourceName=""))}
    fun hasImage()=sourceFile.isFile&&sourceFile.length()>0
    fun sourceSha256():String?{if(!hasImage())return null;val d=java.security.MessageDigest.getInstance("SHA-256");sourceFile.inputStream().use{input->val b=ByteArray(16384);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
    fun save(settings:ReceiptStampSettingsV136):ReceiptStampSettingsV136{val n=ReceiptStampPolicyV136.normalize(settings);require(!n.enabled||hasImage()){"画像スタンプを有効にするにはPNG/JPEG画像を選択してください"};val next=n.copy(stampVersion=load().stampVersion+1);persist(next);return next}
    fun importImage(uri:Uri):ReceiptStampSettingsV136{val bytes=applicationContext.contentResolver.openInputStream(uri)?.use{input->val out=ByteArrayOutputStream();val b=ByteArray(16384);var total=0;while(true){val n=input.read(b);if(n<0)break;total+=n;require(total<=ReceiptStampPolicyV136.MAX_SOURCE_BYTES){"画像は2MB以下のPNG/JPEGを選択してください"};out.write(b,0,n)};out.toByteArray()}?:throw IllegalArgumentException("画像を読み込めませんでした");val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds);ReceiptStampPolicyV136.validateSource(bytes.size,bounds.outWidth,bounds.outHeight);require(bounds.outMimeType=="image/png"||bounds.outMimeType=="image/jpeg"){"PNGまたはJPEG画像を選択してください"};sourceFile.parentFile?.mkdirs();val tmp=File(sourceFile.parentFile,"source_image.tmp");tmp.writeBytes(bytes);if(sourceFile.exists()&&!sourceFile.delete()){tmp.delete();throw IllegalStateException("旧スタンプ画像を更新できませんでした")};require(tmp.renameTo(sourceFile)){"スタンプ画像を保存できませんでした"};val c=load();val next=c.copy(enabled=true,stampVersion=c.stampVersion+1,sourceName=uri.lastPathSegment.orEmpty().substringAfterLast('/').take(120));persist(next);return next}
    fun clearImage():ReceiptStampSettingsV136{val c=load();if(sourceFile.exists())require(sourceFile.delete()){"スタンプ画像を削除できませんでした"};val n=c.copy(enabled=false,stampVersion=c.stampVersion+1,sourceName="");persist(n);return n}
    fun raster(settings:ReceiptStampSettingsV136,paper:ReceiptPaper):MonochromeRasterV136?{if(!settings.enabled||!hasImage())return null;val bitmap=BitmapFactory.decodeFile(sourceFile.absolutePath)?:throw IllegalStateException("スタンプ画像をデコードできませんでした");return try{val p=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(p,0,bitmap.width,0,0,bitmap.width,bitmap.height);ReceiptStampRasterizerV136.rasterize(ArgbImageV136(bitmap.width,bitmap.height,p),settings,paper)}finally{bitmap.recycle()}}
    fun printPrefix(paper:ReceiptPaper):ByteArray{val s=load();return raster(s,paper)?.let(ReceiptStampEscPosV136::encodeRaster)?:ByteArray(0)}
    fun previewBitmap(settings:ReceiptStampSettingsV136,paper:ReceiptPaper):Bitmap?{val r=raster(settings,paper)?:return null;val p=IntArray(r.widthDots*r.heightDots){0xFFFFFFFF.toInt()};for(y in 0 until r.heightDots)for(x in 0 until r.widthDots)if(r.isBlack(x,y))p[y*r.widthDots+x]=0xFF000000.toInt();return Bitmap.createBitmap(p,r.widthDots,r.heightDots,Bitmap.Config.ARGB_8888)}
    private fun persist(s:ReceiptStampSettingsV136){preferences.edit().putBoolean("enabled",s.enabled).putInt("brightness",s.brightness).putInt("threshold",s.threshold).putString("dither",s.dither.name).putInt("rotation_degrees",s.rotationDegrees).putInt("crop_percent",s.cropPercent).putLong("stamp_version",s.stampVersion).putString("source_name",s.sourceName).putString("receipt_placement",s.receiptPlacement.name).putString("receipt_document_type",s.receiptDocumentType.name).apply()}
}

object ReceiptStampPayloadComposerV136 { fun prependToEachDocument(payload:ByteArray,prefix:ByteArray)=ReceiptStampDocumentComposerV136.compose(payload,prefix,ReceiptStampPlacementV136.TOP); fun countDocuments(payload:ByteArray)=ReceiptStampDocumentComposerV136.countDocuments(payload) }

class ReceiptStampGatewayV136(context:Context,private val delegate:PrinterGateway,paperWidthMm:Int):PrinterGateway{
    private val store=ReceiptStampSettingsStoreV136(context.applicationContext);private val paper=ReceiptPaper.fromWidth(paperWidthMm)
    override fun send(payload:ByteArray):Result<Unit>=runCatching{val settings=store.load();val stamp=store.raster(settings,paper)?.let(ReceiptStampEscPosV136::encodeRaster)?:ByteArray(0);val placement=if(settings.enabled)settings.receiptPlacement else ReceiptStampPlacementV136.NONE;delegate.send(ReceiptStampDocumentComposerV136.compose(payload,stamp,placement)).getOrThrow()}
}

@Composable fun ReceiptStampSettingsPanelV136(){
    val context=LocalContext.current.applicationContext;val store=remember(context){ReceiptStampSettingsStoreV136(context)};var revision by remember{mutableIntStateOf(0)};val loaded=remember(revision){store.load()};var enabled by remember(revision){mutableStateOf(loaded.enabled)};var brightnessText by remember(revision){mutableStateOf(loaded.brightness.toString())};var thresholdText by remember(revision){mutableStateOf(loaded.threshold.toString())};var dither by remember(revision){mutableStateOf(loaded.dither)};var rotationDegrees by remember(revision){mutableIntStateOf(loaded.rotationDegrees)};var cropText by remember(revision){mutableStateOf(loaded.cropPercent.toString())};var documentType by remember(revision){mutableStateOf(loaded.receiptDocumentType)};var placement by remember(revision){mutableStateOf(loaded.receiptPlacement)};var message by remember{mutableStateOf("")}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null)runCatching{store.importImage(uri)}.onSuccess{revision++;message="画像スタンプを取込みました（version ${it.stampVersion}）"}.onFailure{message=it.message?:"画像を取込めませんでした"}}
    val brightness=brightnessText.toIntOrNull();val threshold=thresholdText.toIntOrNull();val crop=cropText.toIntOrNull();val draft=if(brightness!=null&&threshold!=null&&crop!=null)loaded.copy(enabled=enabled,brightness=brightness,threshold=threshold,dither=dither,rotationDegrees=rotationDegrees,cropPercent=crop,receiptDocumentType=documentType,receiptPlacement=placement)else null;val valid=draft?.takeIf{it.brightness in -100..100&&it.threshold in 0..255&&it.rotationDegrees in setOf(0,90,180,270)&&it.cropPercent in 0..40}
    val preview58=remember(revision,valid){valid?.let{runCatching{store.previewBitmap(it,ReceiptPaper.MM58)}.getOrNull()}};val preview80=remember(revision,valid){valid?.let{runCatching{store.previewBitmap(it,ReceiptPaper.MM80)}.getOrNull()}}
    Column(Modifier.fillMaxWidth()){
        Spacer(Modifier.height(12.dp));Text("店名画像スタンプ（SCR-720）",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary);Text("PNG/JPEG・2MB以下・2000×2000px以下。58mm=384dot / 80mm=576dot以内。文書種別ごとに上端/下端を割り当てます。",style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={launcher.launch("image/*")},modifier=Modifier.weight(1f)){Text(if(store.hasImage())"画像を差替" else "画像を選択")};OutlinedButton(onClick={runCatching{store.clearImage()}.onSuccess{revision++;message="画像スタンプを削除しました"}.onFailure{message=it.message?:"画像を削除できませんでした"}},enabled=store.hasImage(),modifier=Modifier.weight(1f)){Text("画像を削除")}}
        if(loaded.sourceName.isNotBlank())Text("画像: ${loaded.sourceName} / stampVersion ${loaded.stampVersion}",style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth()){Checkbox(checked=enabled,onCheckedChange={enabled=it},enabled=store.hasImage());Column{Text("画像スタンプを印字");Text("変更は次回印刷から反映",style=MaterialTheme.typography.bodySmall)}}
        Text("文書テンプレート",fontWeight=FontWeight.Bold);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){ReceiptStampDocumentTypeV136.entries.forEach{t->OutlinedButton(onClick={documentType=t;placement=ReceiptStampTemplatePolicyV136.defaultPlacement(t)},modifier=Modifier.weight(1f)){Text(if(documentType==t)"● ${t.displayName}" else t.displayName)}}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){ReceiptStampPlacementV136.entries.forEach{p->OutlinedButton(onClick={placement=p},modifier=Modifier.weight(1f)){Text(if(placement==p)"● ${p.displayName}" else p.displayName)}}}
        Text("切抜き・回転",fontWeight=FontWeight.Bold);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf(0,90,180,270).forEach{d->OutlinedButton(onClick={rotationDegrees=d},modifier=Modifier.weight(1f)){Text(if(rotationDegrees==d)"● ${d}°" else "${d}°")}}};OutlinedTextField(value=cropText,onValueChange={cropText=it.filter(Char::isDigit).take(2)},label={Text("中央切抜き（四辺 0～40%）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(value=brightnessText,onValueChange={brightnessText=it.filter{c->c.isDigit()||c=='-'}.take(4)},label={Text("明るさ（-100～100）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f));OutlinedTextField(value=thresholdText,onValueChange={thresholdText=it.filter(Char::isDigit).take(3)},label={Text("閾値（0～255）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f))}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){ReceiptStampDitherV136.entries.forEach{m->OutlinedButton(onClick={dither=m},modifier=Modifier.weight(1f)){Text(if(dither==m)"● ${m.displayName}" else m.displayName)}}};if(valid==null)Text("設定値を確認してください",color=MaterialTheme.colorScheme.error)
        if(preview58!=null){Text("58mm実幅プレビュー",fontWeight=FontWeight.Bold);Image(preview58.asImageBitmap(),"58mm画像スタンププレビュー",Modifier.fillMaxWidth().height(110.dp),contentScale=ContentScale.Fit)};if(preview80!=null){Text("80mm実幅プレビュー",fontWeight=FontWeight.Bold);Image(preview80.asImageBitmap(),"80mm画像スタンププレビュー",Modifier.fillMaxWidth().height(110.dp),contentScale=ContentScale.Fit)}
        Button(onClick={val c=valid;if(c==null)message="設定値を確認してください" else runCatching{store.save(c)}.onSuccess{revision++;message="画像スタンプ設定を保存しました（version ${it.stampVersion}）"}.onFailure{message=it.message?:"画像スタンプ設定を保存できませんでした"}},enabled=valid!=null&&(!enabled||store.hasImage()),modifier=Modifier.fillMaxWidth()){Text("画像スタンプ設定を保存")};if(message.isNotBlank())Text(message,style=MaterialTheme.typography.bodySmall)
    }
}
