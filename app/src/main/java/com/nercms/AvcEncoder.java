package com.nercms;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;

import static android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
import static android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME;


public class AvcEncoder
{
    private final static String TAG = "MeidaCodec";

    private int TIMEOUT_USEC = 12000;

    private MediaCodec mediaCodec;
    int m_width;
    int m_height;
    int m_framerate;

    public byte[] configbyte;
    DatagramSocket datagramSocket;

    private static int bufferSize  = 80000;
    private static int bufferIndex  = 0;
    private static  byte[] buffer = new byte[bufferSize];



    public AvcEncoder(int width, int height, int framerate, int bitrate) {

        m_width  = width;
        m_height = height;
        m_framerate = framerate;
        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", width, height);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, width*height*5);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        try {
            mediaCodec = MediaCodec.createEncoderByType("video/avc");
        } catch (IOException e) {
            e.printStackTrace();
        }
        //配置编码器参数
        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        //启动编码器
        mediaCodec.start();
        //创建保存编码后数据的文件
//        createfile();

        //建立udp的服务
        try {
            datagramSocket = new DatagramSocket();
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

//    private static String path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/test1.h264";
//    private BufferedOutputStream outputStream;
//
//    private void createfile(){
//        File file = new File(path);
//        if(file.exists()){
//            file.delete();
//        }
//        try {
//            outputStream = new BufferedOutputStream(new FileOutputStream(file));
//        } catch (Exception e){
//            e.printStackTrace();
//        }
//    }

    private void StopEncoder() {
        try {
            mediaCodec.stop();
            mediaCodec.release();
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    public boolean isRuning = false;

    public void StopThread(){
        isRuning = false;
        try {
            StopEncoder();
//            outputStream.flush();
//            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    int count = 0;

    public void StartEncoderThread(){
        Thread EncoderThread = new Thread(new Runnable() {

            @Override
            public void run() {
                isRuning = true;
                byte[] input = null;
                long pts =  0;
                long generateIndex = 0;

                while (isRuning) {
                    long startTime = System.currentTimeMillis();

                    //访问MainActivity用来缓冲待解码数据的队列
                    if (VideoChatActivity.YUVQueue.size() >0){
                        //从缓冲队列中取出一帧
                        input = VideoChatActivity.YUVQueue.poll();
                        byte[] yuv420sp = new byte[m_width*m_height*3/2];
                        //把待编码的视频帧转换为YUV420格式
                        NV21ToNV12(input,yuv420sp,m_width,m_height);
                        input = yuv420sp;
                    }
                    if (input != null) {
                        try {
                            long startMs = System.currentTimeMillis();
                            //编码器输入缓冲区
                            ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
                            //编码器输出缓冲区
                            ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
                            int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);
                            if (inputBufferIndex >= 0) {
                                pts = computePresentationTime(generateIndex);
                                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                                inputBuffer.clear();
                                //把转换后的YUV420格式的视频帧放到编码器输入缓冲区中
                                inputBuffer.put(input);
                                mediaCodec.queueInputBuffer(inputBufferIndex, 0, input.length, pts, 0);
                                generateIndex += 1;
                            }

                            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                            while (outputBufferIndex >= 0) {
                                //Log.i("AvcEncoder", "Get H264 Buffer Success! flag = "+bufferInfo.flags+",pts = "+bufferInfo.presentationTimeUs+"");
                                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                                byte[] outData = new byte[bufferInfo.size];
                                outputBuffer.get(outData);
                                if(bufferInfo.flags == BUFFER_FLAG_CODEC_CONFIG){
                                    configbyte = new byte[bufferInfo.size];
                                    configbyte = outData;
                                }else if(bufferInfo.flags == BUFFER_FLAG_KEY_FRAME){
                                    byte[] keyframe = new byte[bufferInfo.size + configbyte.length];
                                    System.arraycopy(configbyte, 0, keyframe, 0, configbyte.length);
                                    //把编码后的视频帧从编码器输出缓冲区中拷贝出来
                                    System.arraycopy(outData, 0, keyframe, configbyte.length, outData.length);

//                                    Log.d("AvcEncoder", "发送， keyframe.length="+keyframe.length);
                                    DatagramPacket packet = new DatagramPacket(keyframe, keyframe.length, InetAddress.getByName(VideoChatActivity.remote_ip), 9090);
                                    //调用udp的服务发送数据包
                                    datagramSocket.send(packet);
                                    if(keyframe.length>6000) {
//                                        Log.d("AvcEncoder", "发送完成， keyframe.length=" + keyframe.length);
                                    }

//                                    if(bufferIndex+keyframe.length < 6000){
//                                        System.arraycopy(keyframe, 0, buffer, bufferIndex, keyframe.length);
//                                        bufferIndex += keyframe.length;
//                                    }
//                                    else{
//                                        Log.d("AvcEncoder", "发送， buffer.length="+bufferIndex);
//                                        DatagramPacket packet = new DatagramPacket(buffer, bufferIndex, InetAddress.getByName(VideoChatActivity.remote_ip), 9090);
//                                        //调用udp的服务发送数据包
//                                        datagramSocket.send(packet);
//                                        Log.d("AvcEncoder", "发送完成， buffer.length="+bufferIndex);
//
//                                        buffer = new byte[bufferSize];
//                                        bufferIndex=0;
//                                        System.arraycopy(keyframe, 0, buffer, bufferIndex, keyframe.length);
//                                        bufferIndex += keyframe.length;
//                                    }


//                                    outputStream.write(keyframe, 0, keyframe.length);
                                }else{
//                                    //写到文件中
//                                    Log.d("AvcEncoder", "压缩完成， outData.length="+outData.length);
//                                    DatagramPacket packet = new DatagramPacket(outData, outData.length, InetAddress.getByName(VideoChatActivity.remote_ip), 9090);
//                                    Log.d("AvcEncoder", "发送完成， outData.length="+outData.length);
//                                    //调用udp的服务发送数据包
//                                    datagramSocket.send(packet);


                                    DatagramPacket packet = new DatagramPacket(outData, outData.length, InetAddress.getByName(VideoChatActivity.remote_ip), 9090);
                                    //调用udp的服务发送数据包
                                    datagramSocket.send(packet);
                                    if(outData.length>6000) {
//                                        Log.d("AvcEncoder", "发送完成， outData.length=" + outData.length);
                                    }


//                                    if(bufferIndex+outData.length < 6000){
//                                        System.arraycopy(outData, 0, buffer, bufferIndex, outData.length);
//                                        bufferIndex += outData.length;
//                                    }
//                                    else{
//                                        Log.d("AvcEncoder", "发送， outData.length="+bufferIndex);
//                                        DatagramPacket packet = new DatagramPacket(buffer, bufferIndex, InetAddress.getByName(VideoChatActivity.remote_ip), 9090);
//                                        //调用udp的服务发送数据包
//                                        datagramSocket.send(packet);
//                                        Log.d("AvcEncoder", "发送完成， outData.length="+bufferIndex);
//
//                                        buffer = new byte[bufferSize];
//                                        bufferIndex=0;
//                                        System.arraycopy(outData, 0, buffer, bufferIndex, outData.length);
//                                        bufferIndex += outData.length;
//                                    }



//                                    outputStream.write(outData, 0, outData.length);
                                }

                                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                                outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                            }

                        } catch (Throwable t) {
                            Log.e("AvcEncoder", "发送数据异常"+t.getMessage(), t);
                            t.printStackTrace();
                        }
                    } else {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    Log.d("AvcEncoder", "发送耗时:"+(System.currentTimeMillis()-startTime));
                }// end while
            }
        });
        EncoderThread.start();

    }

    private void NV21ToNV12(byte[] nv21,byte[] nv12,int width,int height){
        if(nv21 == null || nv12 == null)return;
        int framesize = width*height;
        int i = 0,j = 0;
        System.arraycopy(nv21, 0, nv12, 0, framesize);
        for(i = 0; i < framesize; i++){
            nv12[i] = nv21[i];
        }
        for (j = 0; j < framesize/2; j+=2)
        {
            nv12[framesize + j-1] = nv21[j+framesize];
        }
        for (j = 0; j < framesize/2; j+=2)
        {
            nv12[framesize + j] = nv21[j+framesize-1];
        }
    }

    /**
     * Generates the presentation time for frame N, in microseconds.
     */
    private long computePresentationTime(long frameIndex) {
        return 132 + frameIndex * 1000000 / m_framerate;
    }
}