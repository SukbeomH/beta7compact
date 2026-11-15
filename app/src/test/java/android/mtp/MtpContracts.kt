package android.mtp
import android.hardware.usb.*
class MtpObjectInfo {
 val name="PUBLIC_TEST.JPG";val compressedSizeLong=4L;val storageId=2;val parent=8
 val dateCreated get()=Wire.nativeEpoch;val dateModified get()=Wire.nativeEpoch
}
class MtpDeviceInfo
class MtpDevice(val device:UsbDevice){
 fun open(connection:UsbDeviceConnection):Boolean {check(!Wire.nativeOwned);Wire.nativeOwned=true;Wire.session=true;Wire.nativeOpens++;return true}
 val deviceInfo get()=MtpDeviceInfo()
 val storageIds get()=intArrayOf(2)
 fun getObjectHandles(storage:Int,format:Int,parent:Int)=intArrayOf(1)
 fun getObjectInfo(handle:Int)=MtpObjectInfo()
 fun getThumbnail(handle:Int):ByteArray {check(Wire.nativeOwned);Wire.thumbnails++;return byteArrayOf(1,2)}
 fun importFile(handle:Int,pfd:android.os.ParcelFileDescriptor):Boolean {check(Wire.nativeOwned);Wire.imports++;pfd.file.writeBytes(byteArrayOf(1,2,3,4));return true}
 // AOSP MtpDevice::close releases interface and fd; closeSession is not invoked.
 fun close(){Wire.nativeOwned=false;Wire.nativeCloses++;if(!Wire.preserveSession)Wire.session=false}
}
