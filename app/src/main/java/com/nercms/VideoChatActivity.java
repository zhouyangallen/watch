package com.nercms;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.nercms.receive.Receive;
import com.nercms.receive.VideoPlayView;
import com.nercms.send.Send;

import org.sipdroid.net.RtpPacket;
import org.sipdroid.net.RtpSocket;
import org.sipdroid.net.SipdroidSocket;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 视频聊天活动
 * Created by zsg on 2016/6/3.
 */
public class VideoChatActivity extends AppCompatActivity {
    private VideoPlayView view = null;
    private SurfaceView surfaceView;
    private SurfaceView surfaceView2;
    private Camera mCamera = null; //创建摄像头处理类
    private SurfaceHolder holder = null; //创建界面句柄，显示视频的窗口句柄
    private Handler handler = new Handler();

    private Send encode;      //编码器
    private Receive decode;   //解码器
    private boolean isRunning; //线程运行标志

    private RtpSocket rtp_socket = null; //创建RTP套接字

    private RtpPacket rtp_send_packet = null; //创建RTP发送包
    private RtpPacket rtp_receive_packet = null; //创建RTP接受包


    //接受 处理
    private long decoder_handle = 0; //拼帧器的句柄
    private byte[] frmbuf = new byte[65536]; //帧缓存
    private byte[] socket_receive_Buffer = new byte[2048]; //包缓存
    private byte[] buffer = new byte[2048];

    private int receveIndex = 0;

    //发送
    private long encoder_handle = -1; //创建编码器的句柄
    private int send_packetNum = 0; //包的数目
    private int[] send_packetSize = new int[200]; //包的尺寸
    private byte[] send_stream = new byte[65536]; //码流
    private byte[] socket_send_Buffer = new byte[65536]; //缓存 stream->socketBuffer->rtp_socket

    public static String remote_ip;
    private int remote_port;


    DatagramSocket datagramSocket;
    DatagramSocket datagramReceiveSocket;

    //创建基本线程池
    final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(1,1,1, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(100));

    MediaCodec mediaCodec;

    private static int yuvqueuesize = 10;

    //待解码视频缓冲队列，静态成员！
    public static ArrayBlockingQueue<byte[]> YUVQueue = new ArrayBlockingQueue<byte[]>(yuvqueuesize);

    //收到的数据缓冲队列，静态成员！
    public static ArrayBlockingQueue<byte[]> ReceiveData = new ArrayBlockingQueue<byte[]>(yuvqueuesize);

    private AvcEncoder avcCodec;

    //解码器
    private MediaCodecUtil codecUtil;
    //读取文件解码线程
    private AvcDecoder avcDecoder;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_chat);
        remote_ip = getIntent().getStringExtra("remote_ip");
        remote_port=getIntent().getIntExtra("remote_port",19888);
        initView();

    }

    private void initView() {
        view = (VideoPlayView) this.findViewById(R.id.video_play);
        surfaceView2 = (SurfaceView) findViewById(R.id.surface_view2);
        surfaceView = (SurfaceView) findViewById(R.id.surface_view);
        holder = surfaceView.getHolder();
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        encode = new Send();
        decode = new Receive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                doStart();
            }
        }, 1000);

    }

    /**
     * 开启 接受 发送rtp线程  开启本地摄像头
     */
    public void doStart() {

        //初始化解码器
        if (rtp_socket == null) {
            try {
//                rtp_socket = new RtpSocket(new SipdroidSocket(19889)); //初始化套接字，20000为接收端口号
                rtp_socket = new RtpSocket(new SipdroidSocket(19888), InetAddress.getByName(remote_ip), remote_port);


                //建立udp的服务
                 datagramSocket = new DatagramSocket();
                datagramReceiveSocket = new DatagramSocket(9090);



            } catch (SocketException e) {
                e.printStackTrace();
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
            //初始化接受包
            rtp_receive_packet = new RtpPacket(socket_receive_Buffer, 0); //初始化 ,socketBuffer改变时rtp_Packet也跟着改变
            /**
             * 因为可能传输数据过大 会将一次数据分割成好几段来传输
             * 接受方 根据序列号和结束符 来将这些数据拼接成完整数据
             */
            //初始化解码器
            decoder_handle = decode.CreateH264Packer(); //创建拼帧器
            decode.CreateDecoder(352, 288); //创建解码器
            isRunning = true;
            DecoderThread decoder = new DecoderThread();
            decoder.start(); //启动一个线程

            //初始化发送包
            rtp_send_packet = new RtpPacket(socket_send_Buffer, 0);
        }
        //初始化编码器
        if (encoder_handle == -1)
            encoder_handle = encode.CreateEncoder(352, 288); //调用底层函数，创建编码器


        if (mCamera == null) {

            //摄像头设置，预览视频
            mCamera = Camera.open(0); //实例化摄像头类对象  0为后置 1为前置
            Camera.Parameters p = mCamera.getParameters(); //将摄像头参数传入p中
            p.setFlashMode("off");
            p.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
            p.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);
            p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            //p.setPreviewFormat(PixelFormat.YCbCr_420_SP); //设置预览视频的格式
            p.setPreviewFormat(ImageFormat.NV21);
            p.setPreviewSize(352, 288); //设置预览视频的尺寸，CIF格式352×288
            //p.setPreviewSize(800, 600);
            p.setPreviewFrameRate(15); //设置预览的帧率，15帧/秒
            mCamera.setParameters(p); //设置参数
            byte[] rawBuf = new byte[1400];
            mCamera.addCallbackBuffer(rawBuf);
            mCamera.setDisplayOrientation(90); //视频旋转90度
            try {
                mCamera.setPreviewDisplay(holder); //预览的视频显示到指定窗口

                //创建AvEncoder对象
                avcCodec = new AvcEncoder(352,288,15,8500*1000);
                //启动编码线程
                avcCodec.StartEncoderThread();
                codecUtil = new MediaCodecUtil(surfaceView2.getHolder());
                codecUtil.startCodec();
                avcDecoder = new AvcDecoder(codecUtil, "");
                avcDecoder.start();

//                mediaCodec = MediaCodec.createEncoderByType("Video/AVC");
//                MediaFormat mediaFormat = MediaFormat.createVideoFormat("Video/AVC", 352, 288);
//                mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 125000);
//                mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
//                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
//                mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
//                mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
//                mediaCodec.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mCamera.startPreview(); //开始预览

            //获取帧
            //预览的回调函数在开始预览的时候以中断方式被调用，每秒调用15次，回调函数在预览的同时调出正在播放的帧
            Callback a = new Callback();
            mCamera.setPreviewCallback(a);
        }
    }

    public void putYUVData(byte[] buffer, int length) {
        if (YUVQueue.size() >= 10) {
            YUVQueue.poll();
        }
        YUVQueue.add(buffer);
    }

    public void putReceiveData(byte[] buffer, int length) {
        if (ReceiveData.size() >= 10) {
            ReceiveData.poll();
        }
        ReceiveData.add(buffer);
    }

    //mCamera回调的类
    class Callback implements Camera.PreviewCallback {
        @Override
        public void onPreviewFrame(byte[] frame, Camera camera) {

//            Log.d("AvcEncoder", Thread.currentThread().getId()+"开始读取摄像头数据"+frame.length);

            //将当前帧图像保存在队列中
            putYUVData(frame,frame.length);
//
//            final byte[] finalFrame = frame;
//            Runnable runnable = new Runnable() {
//                @Override
//                public void run() {
//                    try {
//
//                        Log.d("logcp", Thread.currentThread().getId()+"开始压缩数据");
//
//                        ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
//                        ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
//                        int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);
//                        if (inputBufferIndex >= 0){
//                            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
//                        inputBuffer.clear();
//                        inputBuffer.put(finalFrame, 0, finalFrame.length);
//                        mediaCodec.queueInputBuffer(inputBufferIndex, 0, finalFrame.length, 0, 0);
//                    }
//                    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
//                    int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo,0);
//                        Log.d("logcp", Thread.currentThread().getId()+"开始压缩数据， outputBufferIndex="+outputBufferIndex
//                        +",  "+finalFrame.length);
//                    while (outputBufferIndex >= 0) {
//                        ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
////                        Log.d("logcp", Thread.currentThread().getId()+"压缩后的数据" + outputBuffer.size);
////                        if (frameListener != null)
////                            frameListener.onFrame(outputBuffer, 0, length, flag);
//                        mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
//                        outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
//                    }
////                    DatagramPacket packet = new DatagramPacket(send_stream, send_stream.length, InetAddress.getByName("192.168.0.100"), 9090);
////                        //调用udp的服务发送数据包
////                        datagramSocket.send(packet);
//                    } catch (Exception e) {
//                        Log.e("exzy","捕获异常："+e.getMessage(),e);
//                        e.printStackTrace();
//                    }
//                }
//            };
//
//            threadPoolExecutor.execute(runnable);





//
//            if (encoder_handle != -1) {
//
//                send_stream = frame;
//                send_packetNum = frame.length/65524 +1;
//                if(frame.length<65524){
//                    send_packetSize[0] = frame.length;
//                }
//                else{
//                    send_packetSize[0] = 65524;
//                    int total = frame.length-65524;
//                    for(int i=1;i<200;i++){
//                        if(total < 0){
//                            send_packetSize[i] = 0;
//                        }
//                        if(total>0 && total < 65524){
//                            send_packetSize[i] = total;
//                            total -= 65524;
//                        }
//                        else if(total>0 ){
//                            send_packetSize[i] = 65524;
//                            total -= 65524;
//                        }
//
//                    }
//                }
//
//                //底层函数，返回包的数目，返回包的大小存储在数组packetSize中，返回码流在stream中
////                send_packetNum = encode.EncoderOneFrame(encoder_handle, -1, frame, send_stream, send_packetSize);
//
////                Log.d("log", "原始数据大小：" + frame.length + "  转码后数据大小：" + send_stream.length);
//                if (send_packetNum > 0) {
//
//                    //通过RTP协议发送帧
//                    final int[] pos = {0}; //从码流头部开始取
//                    final long timestamp = System.currentTimeMillis(); //设定时间戳
//                    /**
//                     * 因为可能传输数据过大 会将一次数据分割成好几段来传输
//                     * 接受方 根据序列号和结束符 来将这些数据拼接成完整数据
//                     */
//                    new Thread(new Runnable() {
//                        @Override
//                        public void run() {
//
//
//
//
//
//
//                            try{
//                                //创建了一个数据包
//                                String data = "这个是我第一个udp的例子..";
//                                Log.d("log","1发送数据"+send_stream.length);
//                                DatagramPacket packet = new DatagramPacket(send_stream, send_stream.length,InetAddress.getByName("192.168.0.100") , 9090);
//                                //调用udp的服务发送数据包
//                                datagramSocket.send(packet);
//                                Log.d("log","2发送数据"+send_stream.length);
//                            } catch (Exception e) {
//                                e.printStackTrace();
//                            }
//
//
//
//
//
//
//
//                            int sequence = 0; //初始化序列号
//                            for (int i = 0; i < send_packetNum; i++) {
//
//                                rtp_send_packet.setPayloadType(2);//定义负载类型，视频为2
//                                rtp_send_packet.setMarker(i == send_packetNum - 1 ? true : false); //是否是最后一个RTP包
//                                rtp_send_packet.setSequenceNumber(sequence++); //序列号依次加1
//                                rtp_send_packet.setTimestamp(timestamp); //时间戳
//                                //Log.d("log", "序列号:" + sequence + " 时间：" + timestamp);
//                                rtp_send_packet.setPayloadLength(send_packetSize[i]); //包的长度，packetSize[i]+头文件
//                                //从码流stream的pos处开始复制，从socketBuffer的第12个字节开始粘贴，packetSize为粘贴的长度
//                                System.arraycopy(send_stream, pos[0], socket_send_Buffer, 12, send_packetSize[i]); //把一个包存在socketBuffer中
//                                pos[0] += send_packetSize[i]; //重定义下次开始复制的位置
//                                //rtp_packet.setPayload(socketBuffer, rtp_packet.getLength());
//                                //  Log.d("log", "序列号:" + sequence + " bMark:" + rtp_packet.hasMarker() + " packetSize:" + packetSize[i] + " tPayloadType:2" + " timestamp:" + timestamp);
//                                try {
//                                    rtp_socket.send(rtp_send_packet);
//                                } catch (IOException e) {
//                                    e.printStackTrace();
//                                }
//
//                            }
//                        }
//                    }).start();
//
//
//                }
//            }
//            else{
//
//            }
        }// end onPreviewFrame
    }

    /**
     * 接收rtp数据并解码 线程
     */
    class DecoderThread extends Thread {

        @Override
        public void run() {

            Log.d("AvcDecoder","接受数据run方法");

            while (isRunning) {
                Log.d("AvcDecoder","接受数据进入while");
                try{
                    //准备空的数据包用于存放数据。
                    byte[] buf = new byte[65536];
                    DatagramPacket datagramPacket = new DatagramPacket(buf, buf.length); // 1024
                    //调用udp的服务接收数据
                    datagramReceiveSocket.receive(datagramPacket); //receive是一个阻塞型的方法，没有接收到数据包之前会一直等待。 数据实际上就是存储到了byte的自己数组中了。
                    Log.d("AvcDecoder","收到数据"+datagramPacket.getLength());
                    byte[] realData = new byte[datagramPacket.getLength()];
                    System.arraycopy(datagramPacket.getData(), datagramPacket.getOffset(), realData, 0, datagramPacket.getLength()); //把一个包存在socketBuffer中
                    putReceiveData(realData, realData.length);

                } catch (IOException e) {
                    e.printStackTrace();
                }






//                try {
//                    Log.d("log","开始接受数据");
//                    rtp_socket.receive(rtp_receive_packet); //接收一个包
//                    Log.d("log","收到数据");
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//                int packetSize = rtp_receive_packet.getPayloadLength(); //获取包的大小
//
//                if (packetSize <= 0)
//                    continue;
//                if (rtp_receive_packet.getPayloadType() != 2) //确认负载类型为2
//                    continue;
//                System.arraycopy(socket_receive_Buffer, 12, buffer, 0, packetSize); //socketBuffer->buffer
//                int sequence = rtp_receive_packet.getSequenceNumber(); //获取序列号
//                long timestamp = rtp_receive_packet.getTimestamp(); //获取时间戳
//                int bMark = rtp_receive_packet.hasMarker() == true ? 1 : 0; //是否是最后一个包
////                int frmSize = decode.PackH264Frame(decoder_handle, buffer, packetSize, bMark, (int) timestamp, sequence, frmbuf); //packer=拼帧器，frmbuf=帧缓存
////                Log.d("log", "序列号:" + sequence + " bMark:" + bMark + " packetSize:" + packetSize + " PayloadType:" + rtp_receive_packet.getPayloadType() + " timestamp:" + timestamp + " frmSize:" + frmSize);
////                if (frmSize <= 0)
////                    continue;
//
////                decode.DecoderNal(frmbuf, frmSize, view.mPixel);//解码后的图像存在mPixel中
//
//                System.arraycopy(socket_receive_Buffer, 12, view.mPixel, receveIndex, packetSize); //socketBuffer->buffer
//                receveIndex += packetSize;
//
//                if(bMark==1){
//                    Log.d("log","序列号:"+sequence+" 包大小："+packetSize+" 时间："+timestamp+"  frmbuf[30]:"+frmbuf[30]);
//                    view.postInvalidate();
//                    receveIndex = 0;
//                }














            }

            //关闭
            if (decoder_handle != 0) {
                decode.DestroyH264Packer(decoder_handle);
                decoder_handle = 0;
            }
            if (rtp_socket != null) {
                rtp_socket.close();
                rtp_socket = null;
            }
            decode.DestoryDecoder();
        }
    }

    /**
     * 关闭摄像头 并释放资源
     */
    public void close() {
        isRunning = false;
        //释放摄像头资源
        if (mCamera != null) {
            mCamera.setPreviewCallback(null); //停止回调函数
            mCamera.stopPreview(); //停止预览
            mCamera.release(); //释放资源
            mCamera = null; //重新初始化
        }

        if (rtp_socket != null) {
            rtp_socket.close();
            rtp_socket = null;
        }

        //关闭资源 ---实际上就是释放占用的端口号
        if(datagramSocket!=null) {
            datagramSocket.close();
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        close();
    }
}
