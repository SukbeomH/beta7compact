// Ported from independent R2 UsbContracts.kt; synthetic USB/PTP contract only.
package android.hardware.usb
import dev.sukbeomh.beta7compact.usb.*
object UsbConstants {const val USB_ENDPOINT_XFER_BULK=2;const val USB_DIR_IN=128;const val USB_DIR_OUT=0}
class UsbEndpoint(val type:Int,val direction:Int)
class UsbInterface(val id:Int=0,val alternateSetting:Int=0,val interfaceClass:Int=6){
 val endpointCount=2
 fun getEndpoint(n:Int)=UsbEndpoint(2,if(n==0)128 else 0)
}
class UsbConfiguration(val id:Int=1,val faces:List<UsbInterface> = listOf(UsbInterface())) {
 val interfaceCount get()=faces.size
 fun getInterface(i:Int)=faces[i]
}
class UsbDevice(val cfgs:List<UsbConfiguration> = listOf(UsbConfiguration())){
 val vendorId=0x054c;val configurationCount get()=cfgs.size;val interfaceCount get()=cfgs.first().interfaceCount
 fun getConfiguration(i:Int)=cfgs[i]
 fun getInterface(i:Int)=cfgs.first().getInterface(i)
}
object Wire {
 var session=false;var nativeOwned=false;var preserveSession=true;var stallAlternate=false
 var rawParent=8;var openResponse=0x2001;var nativeEpoch=1L;var imports=0;var thumbnails=0;var controls=0
 var nativeOpens=0;var nativeCloses=0;var rawOpens=0;var setCalls=0;var closed=0
 var nativeFailure:String?=null
 val operations=mutableListOf<Int>()
 val date="20200308T023000.1Z"
 fun reset(persist:Boolean=true,stall:Boolean=false){session=false;nativeOwned=false;preserveSession=persist;stallAlternate=stall;nativeOpens=0;nativeCloses=0;rawOpens=0;setCalls=0;closed=0;operations.clear();rawParent=8;openResponse=0x2001;nativeEpoch=1L;imports=0;thumbnails=0;controls=0;nativeFailure=null}
 fun str(s:String)=byteArrayOf((s.length+1).toByte())+(s+"\u0000").toByteArray(Charsets.UTF_16LE)
 fun objectInfo()=buffer(52).putInt(2).putShort(0x3801.toShort()).putShort(0).putInt(4).apply{position(38);putInt(rawParent);position(52)}.array()+str("PUBLIC_TEST.JPG")+str(date)+byteArrayOf(0,0)
}
class UsbManager(val dev:UsbDevice=UsbDevice()){
 val deviceList=hashMapOf("synthetic" to dev)
 fun hasPermission(d:UsbDevice)=true
 fun openDevice(d:UsbDevice)=UsbDeviceConnection()
}
class UsbDeviceConnection {
 private var pending=byteArrayOf()
 fun controlTransfer(type:Int,request:Int,value:Int,index:Int,result:ByteArray,length:Int,timeout:Int):Int {
  Wire.controls++
  check(!Wire.nativeOwned){"raw/native USB ownership overlapped"}
  if(type and 128==0){Wire.setCalls++;error("unexpected USB SET")}
  if(request==8){result[0]=1;return 1}
  if(request==10){if(Wire.stallAlternate)return -1;result[0]=0;return 1}
  error("unexpected read")
 }
 fun claimInterface(face:UsbInterface,force:Boolean):Boolean {check(!Wire.nativeOwned);Wire.rawOpens++;return true}
 fun setConfiguration(c:UsbConfiguration):Boolean{Wire.setCalls++;return true}
 fun setInterface(f:UsbInterface):Boolean{Wire.setCalls++;return true}
 fun releaseInterface(face:UsbInterface)=true
 fun close(){Wire.closed++}
 fun bulkTransfer(ep:UsbEndpoint,bytes:ByteArray,size:Int,timeout:Int):Int {
  check(!Wire.nativeOwned)
  if(ep.direction==0){
   val cmd=PtpContainer.decode(bytes.copyOf(size));Wire.operations.add(cmd.code)
   var response=Ptp.OK;var data:ByteArray?=null
   when(cmd.code){
    0x1001->data=byteArrayOf(0)
    0x1002->if(Wire.openResponse!=Ptp.OK)response=Wire.openResponse else if(Wire.session)response=0x201e else Wire.session=true
    0x1003->Wire.session=false
    0x1004->data=buffer(8).putInt(1).putInt(2).array()
    0x1007->data=buffer(8).putInt(1).putInt(1).array()
    0x1008->data=Wire.objectInfo()
    0x1009->data=byteArrayOf(1,2,3,4)
    0x100A->data=byteArrayOf(1,2)
    else->error("unexpected opcode")
   }
   pending=(if(data!=null)PtpContainer(2,cmd.code,cmd.transaction,data).encode() else byteArrayOf())+
     PtpContainer(3,response,cmd.transaction,if(response==0x201e)buffer(4).putInt(1).array() else byteArrayOf()).encode()
   return size
  }
  if(pending.isEmpty())return -1
  val n=minOf(size,pending.size);pending.copyInto(bytes,0,0,n);pending=pending.copyOfRange(n,pending.size);return n
 }
}
