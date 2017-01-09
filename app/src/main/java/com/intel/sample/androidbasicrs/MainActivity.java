// Copyright (c) 2014 Intel Corporation. All rights reserved.
//
// WARRANTY DISCLAIMER
//
// THESE MATERIALS ARE PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL INTEL OR ITS
// CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
// EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
// PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
// PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
// OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY OR TORT (INCLUDING
// NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THESE
// MATERIALS, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//
// Intel Corporation is the author of the Materials, and requests that all
// problem reports or change requests be submitted to it directly


package com.intel.sample.androidbasicrs;

import java.io.IOException;

import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.renderscript.Allocation;
import android.renderscript.RenderScript;
import android.util.Log;
import android.view.MotionEvent;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;

import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;


public class MainActivity extends Activity
{
    private Bitmap inputBitmap;
    private Bitmap outputBitmap;
    private Bitmap outputBitmapStage;

    private ImageView outputImageView;
    private Thread backgroundThread;
    private ConditionVariable isGoing;
    private volatile boolean isShuttingDown = false;

    private ConditionVariable isRendering;
    private int stepCount;

    private int xTouchUI;
    private int yTouchUI;
    private int stepTouchUI;

    private int xTouchApply;
    private int yTouchApply;
    private int stepTouchApply;

    private RenderScript rs;
    private ScriptC_process script;
    private Allocation allocationIn;
    private Allocation allocationOut;

    private File image;
    private String  newBitmapPath;

    private static final int CAMERA_SHOT = 1;

    private long stepStart;
    private long stepEnd;
    private long prevFrameTimestamp;

    private int itersAccum;
    private long frameDurationAccum;
    private long effectDurationAccum;

    private static final long maxFrameDurationAccum = 500000000;

    private TextView FPSLabel;
    private TextView frameDurationLabel;
    private TextView effectDurationLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        outputImageView = (ImageView)findViewById(R.id.outputImageView);
        FPSLabel = (TextView)findViewById(R.id.FPS);
        frameDurationLabel = (TextView)findViewById(R.id.FrameDuration);
        effectDurationLabel = (TextView)findViewById(R.id.EffectDuration);
        isGoing  = new ConditionVariable(false);
        isRendering = new ConditionVariable(true);
        image = null;
        newBitmapPath = null;

        initRS();

        ResetTouch();
    }

    @Override
    protected void onDestroy()
    {
        Log.i("AndroidBasic", "onDestroy");

        isShuttingDown = true;
        isGoing.open();
        super.onDestroy();
    }

    protected void initRS()
    {
        rs = RenderScript.create(this);
        script = new ScriptC_process(rs);
    }

    public void PhotoOnClick(View v)
    {
        Intent cameraShotIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File mediaFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        mediaFolder.mkdir();

        image = new File(mediaFolder, timeStamp + ".jpg");
        Log.i("AndroidBasic", "file name for the intent: " + Uri.fromFile(mediaFolder) + "/" + timeStamp+ ".jpg");
        try
        {
            if(image.createNewFile())
            {
                cameraShotIntent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, Uri.fromFile(image));
                if (cameraShotIntent.resolveActivity(getPackageManager()) != null)
                {
                     startActivityForResult(cameraShotIntent, CAMERA_SHOT);
                }
            }
        }
        catch (IOException ex)
        {
            ex.printStackTrace();
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode == CAMERA_SHOT)
        {
            if(resultCode != RESULT_OK)
            {
                image.delete();
                return;
            }
            // Reset the previous touches.
            ResetTouch();

            newBitmapPath =  image.getAbsolutePath();
            Log.i("AndroidBasic", "newBitmapPath: " + newBitmapPath);

            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            Uri contentUri = Uri.fromFile(image);
            mediaScanIntent.setData(contentUri);
            this.sendBroadcast(mediaScanIntent);
        }
    }

    private void startBackgroundThread ()
    {
        backgroundThread = new Thread(new Runnable() {
            public void run() {
                while(!isShuttingDown)
                {
                    isGoing.block();
                    isRendering.block();
                    {
                        if(newBitmapPath!=null)
                        {
                            loadInputImage(newBitmapPath);
                            newBitmapPath = null;

                        }
                        Log.i("AndroidBasic", "beforeStep");
                        {
                            // Swap target and staging bitmap objects.
                            Bitmap t = outputBitmap;
                            outputBitmap = outputBitmapStage;
                            outputBitmapStage = t;
                        }
                        stepStart = System.nanoTime();
                        step();
                        stepEnd = System.nanoTime();

                        Log.i("AndroidBasic", "afterStep");
                        isRendering.close();
                        outputImageView.post
                        (
                            new Runnable()
                            {
                                public void run ()
                                {
                                    {
                                        xTouchApply = xTouchUI;
                                        yTouchApply = yTouchUI;
                                        stepTouchApply = stepTouchUI;

                                        updatePerformanceStats();

                                        Log.i("AndroidBasic", "setImageBitmap and invalidate");
                                        outputImageView.setImageBitmap(outputBitmap);
                                        outputImageView.invalidate();
                                    }
                                    isRendering.open();
                                }
                            }
                        );
                    }
                }
                Log.i("AndroidBasic", "Exiting backgroundThread");
            }
        });

        backgroundThread.start();
    }

    private void updatePerformanceStats()
    {
        long curFrameTimestamp = System.nanoTime();

        if(prevFrameTimestamp != -1)
        {
            // Calculate the current frame duration value.
            long frameDuration = curFrameTimestamp - prevFrameTimestamp;
            long effectDuration = stepEnd - stepStart;
            frameDurationAccum += frameDuration;
            effectDurationAccum += effectDuration;
            itersAccum++;

            if(frameDurationAccum > maxFrameDurationAccum)
            {
                frameDuration  = frameDurationAccum / itersAccum;
                effectDuration = effectDurationAccum / itersAccum;
                frameDurationAccum = 0;
                effectDurationAccum = 0;
                itersAccum = 0;

                FPSLabel.setText((float)(int)((1e9f / frameDuration)*10)/10 + " FPS");
                frameDurationLabel.setText("Frame: " + frameDuration / 1000000 + " ms");
                effectDurationLabel.setText("Effect:  " + effectDuration / 1000000 + " ms");
            }
        }

        prevFrameTimestamp = curFrameTimestamp;
    }

    // This method runs in a separate working thread.
    private void step ()
    {
        Log.i("AndroidBasic", "step");
        stepRenderScript();
        stepCount++;
    }

    @Override
    protected void onStart()
    {
        super.onStart();

        // Unleash the "worker" thread.
        isGoing.open();
        Log.i("AndroidBasic", "onStart");
    }

    @Override
    protected void onStop()
    {
        super.onStop();

        // Stop the background filtering thread.
        isGoing.close();
        Log.i("AndroidBasic", "onStop");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);

        Log.i("AndroidBasic", "onWindowFocusChanged");
        // When the application is restarted, which means the inputBitmap doesn't exist,
        // load inputBitmap first and (re)start the worker thread.
        // If the inputBitmap already exists, the application is likely to resume from the minimized state.
        if(inputBitmap == null)
        {
            String fromResources = null;
            loadInputImage(fromResources);
            startBackgroundThread();
        }
    }

    private void loadInputImage (String path)
    {
        int displayWidth = outputImageView.getWidth();
        int displayHeight = outputImageView.getHeight();
        if(Build.FINGERPRINT.startsWith("generic")) //emulator, we are very memory limited
        {
            displayWidth  /=2;
            displayHeight /=2;
        }

        Log.i("AndroidBasic", "display dimensions: " + displayWidth + ", " + displayHeight);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;    // This avoids the decoding itself and reads the image statistics.

        if(path==null)
        {
            BitmapFactory.decodeResource(getResources(), R.drawable.picture, options);
        }
        else
        {
            BitmapFactory.decodeFile(path, options);
        }

        int origWidth  = options.outWidth;
        int origHeight = options.outHeight;

        options.inSampleSize = Math.min(origWidth/displayWidth, origHeight/displayHeight);
        options.inJustDecodeBounds = false;
        if(path==null)
        {
            inputBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.picture, options);
        }
        else
        {
            inputBitmap = BitmapFactory.decodeFile(path, options);
        }

        inputBitmap = Bitmap.createScaledBitmap(inputBitmap, displayWidth, displayHeight, false);

        allocationIn = Allocation.createFromBitmap(
            rs,
            inputBitmap,
            Allocation.MipmapControl.MIPMAP_NONE,
            Allocation.USAGE_SCRIPT
        );
        allocationOut =  Allocation.createTyped(rs, allocationIn.getType());

        int imageWidth  = inputBitmap.getWidth();
        int imageHeight = inputBitmap.getHeight();

        outputBitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
        outputBitmapStage = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event)
    {
        xTouchUI = (int)(event.getX());
        yTouchUI = (int)(event.getY());

        stepTouchUI = stepCount;

        Log.i("AndroidBasic", "x = " + event.getX() + ", y = " + event.getY());
        return super.onTouchEvent(event);
    }


    private void stepRenderScript ()
    {
        int radius = (stepTouchApply == -1 ? -1 : 10*(stepCount - stepTouchApply));
        int radiusHi = (radius + 2)*(radius + 2);
        int radiusLo = (radius - 2)*(radius - 2);

        script.set_height(inputBitmap.getHeight());
        script.set_width(inputBitmap.getWidth());
        script.set_radiusHi(radiusHi);
        script.set_radiusLo(radiusLo);
        script.set_xTouchApply(xTouchApply);
        script.set_yTouchApply(yTouchApply);
        script.set_input(allocationIn);

        script.forEach_root(allocationIn, allocationOut);
        allocationOut.copyTo(outputBitmap);

    }

    private void ResetTouch()
    {
        stepTouchUI = stepTouchApply = -1;
    }

}
