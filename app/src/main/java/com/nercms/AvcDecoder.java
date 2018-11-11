package com.nercms;

import android.util.Log;

import com.nercms.MediaCodecUtil;
import com.nercms.VideoChatActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Created by Administrator on 2018/11/11.
 */

public class AvcDecoder  extends Thread {
    //解码器
    private MediaCodecUtil util;
    //文件路径
    private String path;
    //文件读取完成标识
    private boolean isFinish = false;
    //这个值用于找到第一个帧头后，继续寻找第二个帧头，如果解码失败可以尝试缩小这个值
    private static final int FRAME_MIN_LEN = 20;
    //一般H264帧大小不超过200k,如果解码失败可以尝试增大这个值
    private static final int FRAME_MAX_LEN = 300 * 1024;
    //根据帧率获取的解码每帧需要休眠的时间,根据实际帧率进行操作
    private static final int PRE_FRAME_TIME = 1000 / 25;
    //按帧用来缓存h264数据
//    private ArrayList<byte[]> frameList;
    private static ArrayBlockingQueue<byte[]> frameData = new ArrayBlockingQueue<byte[]>(100);
    //缓存最多的帧数
    private static final int MAX_FRAME_SIZE = 100;

    /**
     * 初始化解码器
     *
     * @param util 解码 Util
     * @param path 文件路径
     */
    public AvcDecoder(MediaCodecUtil util, String path) {
        this.util = util;
        this.path = path;
//        frameList = new ArrayList<>();
        //开启解码线程
        new DecodeThread().start();
    }

    /**
     * 寻找指定 buffer 中 h264 头的开始位置
     *
     * @param data   数据
     * @param offset 偏移量
     * @param max    需要检测的最大值
     * @return h264头的开始位置 ,-1表示未发现
     */
    private int findHead(byte[] data, int offset, int max) {
        int i;
        for (i = offset; i <= max; i++) {
            //发现帧头
            if (isHead(data, i))
                break;
        }
        //检测到最大值，未发现帧头
        if (i == max) {
            i = -1;
        }
        return i;
    }

    /**
     * 判断是否是I帧/P帧头:
     * 00 00 00 01 65    (I帧)
     * 00 00 00 01 61 / 41   (P帧)
     * 00 00 00 01 67    (SPS)
     * 00 00 00 01 68    (PPS)
     *
     * @param data   解码数据
     * @param offset 偏移量
     * @return 是否是帧头
     */
    private boolean isHead(byte[] data, int offset) {
        boolean result = false;
        // 00 00 00 01 x
        if (data[offset] == 0x00 && data[offset + 1] == 0x00
                && data[offset + 2] == 0x00 && data[3] == 0x01 && isVideoFrameHeadType(data[offset + 4])) {
            result = true;
        }
        // 00 00 01 x
        if (data[offset] == 0x00 && data[offset + 1] == 0x00
                && data[offset + 2] == 0x01 && isVideoFrameHeadType(data[offset + 3])) {
            result = true;
        }
        return result;
    }

    /**
     * I帧或者P帧
     */
    private boolean isVideoFrameHeadType(byte head) {
        return head == (byte) 0x65 || head == (byte) 0x61 || head == (byte) 0x41
                || head == (byte) 0x67 || head == (byte) 0x68;
    }

    @Override
    public void run() {
        super.run();

//        File file = new File(path);
        //判断文件是否存在
//        if (file.exists()) {
            try {
//                FileInputStream fis = new FileInputStream(file);
                //保存完整数据帧
                byte[] frame = new byte[FRAME_MAX_LEN];
                //当前帧长度
                int frameLen = 0;

//                byte[] readData = new byte[10 * 1024];
                //循环读取数据
                while (!isFinish) {

                    long startTime = System.currentTimeMillis();

                    //每次从文件读取的数据
                    if (VideoChatActivity.ReceiveData.size() ==0) {
                        continue;
                    }
                    byte[] readData = VideoChatActivity.ReceiveData.poll();
                    int readLen = readData.length;
                    //加入缓存List
                    addFrame(readData);




////                    if (fis.available() > 0) {
////                        int readLen = fis.read(readData);
//                        //当前长度小于最大值
//                        if (frameLen + readLen < FRAME_MAX_LEN) {
//                            //将readData拷贝到frame
//                            System.arraycopy(readData, 0, frame, frameLen, readLen);
//                            //修改frameLen
//                            frameLen += readLen;
//                            //寻找第一个帧头
//                            int headFirstIndex = findHead(frame, 0, frameLen);
//                            while (headFirstIndex >= 0 && isHead(frame, headFirstIndex)) {
//                                //寻找第二个帧头
//                                int headSecondIndex = findHead(frame, headFirstIndex + FRAME_MIN_LEN, frameLen);
//                                //如果第二个帧头存在，则两个帧头之间的就是一帧完整的数据
//                                if (headSecondIndex > 0 && isHead(frame, headSecondIndex)) {
////                                    Log.e("TAG", "headSecondIndex:" + headSecondIndex);
//                                    //加入缓存List
//                                    addFrame(Arrays.copyOfRange(frame, headFirstIndex, headSecondIndex));
//                                    //截取headSecondIndex之后到frame的有效数据,并放到frame最前面
//                                    byte[] temp = Arrays.copyOfRange(frame, headSecondIndex, frameLen);
//                                    System.arraycopy(temp, 0, frame, 0, temp.length);
//                                    //修改frameLen的值
//                                    frameLen = temp.length;
//                                    //继续寻找数据帧
//                                    headFirstIndex = findHead(frame, 0, frameLen);
//                                } else {
//                                    //找不到第二个帧头
//                                    headFirstIndex = -1;
//                                }
//                            }
//                        } else {
//                            //如果长度超过最大值，frameLen置0
//                            frameLen = 0;
//                        }




                    Log.e("AvcEncoder", "接受耗时:" +(System.currentTimeMillis()-startTime) );









//                    } else {
//                        //文件读取结束
//                        isFinish = true;
//                    }
                }// end while
            } catch (Exception e) {
                e.printStackTrace();
            }
//        } else {
//            Log.e("TAG", "File not found");
//        }
    }

    //视频解码
    private void onFrame(byte[] frame, int offset, int length) {
        if (util != null) {
            try {
//                long s = System.currentTimeMillis();
                util.onFrame(frame, offset, length);
//                Log.e("DecodeFileTime", id + " : " + (System.currentTimeMillis() - s));
            } catch (Exception e) {
                Log.e("avc", "异常"+e.getMessage(), e);
                e.printStackTrace();
            }
        } else {
            Log.e("TAG", "mediaCodecUtil is NULL");
        }
    }

    //修眠
    private void sleepThread(long startTime, long endTime) {
        //根据读文件和解码耗时，计算需要休眠的时间
        long time = PRE_FRAME_TIME - (endTime - startTime);
        if (time > 0) {
            try {
                Thread.sleep(time);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    //将视频数据添加到缓存List
    private void addFrame(byte[] frame) throws InterruptedException {
        if(frameData.size()>100){
            frameData.poll();
        }
        frameData.add(frame);
        //当长度多于MAX_FRAME_SIZE时,休眠2秒，避免OOM
//        if (frameList.size() > MAX_FRAME_SIZE) {
//            Thread.sleep(2000);
//        }
    }

    //手动终止读取文件，结束线程
    public void stopThread() {
        isFinish = true;
    }

    /**
     * 解码线程
     */
    private class DecodeThread extends Thread {
        @Override
        public void run() {
            super.run();
            long start;
            while (!isFinish || frameData.size() > 0) {
                byte[] frame = frameData.poll();
                if(frame==null)
                    continue;
                onFrame(frame, 0, frame.length);
//                start = System.currentTimeMillis();
//                if (frameList != null && frameList.size() > 0) {
//                    onFrame(frameList.get(0), 0, frameList.get(0).length);
//                    //移除已经解码的数据
//                    frameList.remove(0);
//                }
                //休眠
//                sleepThread(start, System.currentTimeMillis());
            }
        }
    }
}