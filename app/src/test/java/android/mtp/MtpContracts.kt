package android.mtp
import android.hardware.usb.*
class MtpObjectInfo {
 val name="PUBLIC_TEST.JPG";val compressedSizeLong=4L;val storageId=2;val parent=8
 val dateCreated get()=Wire.nativeEpoch;val dateModified get()=Wire.nativeEpoch
}
class MtpDeviceInfo
class MtpDevice(val device:UsbDevice){
 fun open(connection:UsbDeviceConnection):Boolean {check(!Wire.nativeOwned);Wire.nativeOwned=true;Wire.session=true;Wire.nativeOpens++;return Wire.nativeFailure!="open"}
 val deviceInfo get()=if(Wire.nativeFailure=="info")null else MtpDeviceInfo()
 val storageIds get()=if(Wire.nativeFailure=="storage")null else intArrayOf(2)
 fun getObjectHandles(storage:Int,format:Int,parent:Int)=if(Wire.nativeFailure=="handles")null else intArrayOf(1)
 fun getObjectInfo(handle:Int)=if(Wire.nativeFailure=="object")null else MtpObjectInfo()
 fun getThumbnail(handle:Int):ByteArray? {check(Wire.nativeOwned);Wire.thumbnails++;return if(Wire.nativeFailure=="thumb")null else byteArrayOf(1,2)}
 fun importFile(handle:Int,pfd:android.os.ParcelFileDescriptor):Boolean {check(Wire.nativeOwned);Wire.imports++;pfd.file.writeBytes(if(Wire.nativeFailure=="download")byteArrayOf(9,9,9,9,9,9) else byteArrayOf(1,2,3,4));return Wire.nativeFailure!="download"}
 // AOSP MtpDevice::close releases interface and fd; closeSession is not invoked.
 fun close(){Wire.nativeOwned=false;Wire.nativeCloses++;if(!Wire.preserveSession)Wire.session=false}
}
