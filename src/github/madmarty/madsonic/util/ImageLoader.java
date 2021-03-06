/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package github.madmarty.madsonic.util;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.graphics.Shader.TileMode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.media.RemoteControlClient;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import github.madmarty.madsonic.R;
import github.madmarty.madsonic.domain.MusicDirectory;
import github.madmarty.madsonic.service.MusicService;
import github.madmarty.madsonic.service.MusicServiceFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Asynchronous loading of images, with caching.
 * <p/>
 * There should normally be only one instance of this class.
 *
 * @author Sindre Mehus
 */
@TargetApi(14)
public class ImageLoader implements Runnable {

    private static final String TAG = ImageLoader.class.getSimpleName();
    private static final int CONCURRENCY = 5;

    private final LRUCache<String, Drawable> cache = new LRUCache<String, Drawable>(100);
    private final BlockingQueue<Task> queue;
    private final int imageSizeDefault;
    private final int imageSizeLarge;
    private Drawable largeUnknownImage;

    public ImageLoader(Context context) {
        queue = new LinkedBlockingQueue<Task>(500);

        // Determine the density-dependent image sizes.
        imageSizeDefault = (int) Math.round((context.getResources().getDrawable(R.drawable.unknown_album).getIntrinsicHeight())); //  *  1.25);
    //    Log.d(TAG, "imageSizeDefault: " + imageSizeDefault );
        
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        imageSizeLarge = (int) Math.round(Math.min(metrics.widthPixels, metrics.heightPixels) * 0.6);
   //     Log.d(TAG, "imageSizeLarge: " + imageSizeDefault );

        for (int i = 0; i < CONCURRENCY; i++) {
            new Thread(this, "ImageLoader").start();
        }

        createLargeUnknownImage(context);
    }

    private void createLargeUnknownImage(Context context) {
        BitmapDrawable drawable = (BitmapDrawable) context.getResources().getDrawable(R.drawable.unknown_album_large);
        Bitmap bitmap = Bitmap.createScaledBitmap(drawable.getBitmap(), imageSizeLarge, imageSizeLarge, true);
        bitmap = createReflection(bitmap);
        largeUnknownImage = Util.createDrawableFromBitmap(context, bitmap);
    }

    public void loadImage(View view, MusicDirectory.Entry entry, boolean large, boolean crossfade) {
        if (entry == null || entry.getCoverArt() == null) {
            setUnknownImage(view, large);
            return;
        }

        int size = large ? imageSizeLarge : imageSizeDefault;
        Drawable drawable = cache.get(getKey(entry.getCoverArt(), size));
        if (drawable != null) {
            setImage(view, drawable, large);
            return;
        }

        if (!large) {
            setUnknownImage(view, large);
        }
        queue.offer(new Task(view.getContext(), view, null, entry, size, large, large, crossfade));
    }

    public void loadImage(Context context, RemoteControlClient remoteControl, MusicDirectory.Entry entry) {
        if (entry == null || entry.getCoverArt() == null) {
            setUnknownImage(remoteControl);
            return;
        }
        
        Drawable drawable = cache.get(getKey(entry.getCoverArt(), imageSizeDefault));
        if (drawable != null) {
            setImage(remoteControl, drawable);
            return;
        }

        setUnknownImage(remoteControl);
        queue.offer(new Task(context, null, remoteControl, entry, imageSizeDefault, false, false, false));
    }

    private String getKey(String coverArtId, int size) {
        return coverArtId + size;
    }

    private void setImage(View view, Drawable drawable, boolean crossfade) {
        if (view instanceof TextView) {
            // Cross-fading is not implemented for TextView since it's not in use.  It would be easy to add it, though.
            TextView textView = (TextView) view;
            textView.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        } else if (view instanceof ImageView) {
            ImageView imageView = (ImageView) view;
            if (crossfade) {

                Drawable existingDrawable = imageView.getDrawable();
                if (existingDrawable == null) {
                    Bitmap emptyImage = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                    existingDrawable = new BitmapDrawable(emptyImage);
                }

                Drawable[] layers = new Drawable[]{existingDrawable, drawable};

                TransitionDrawable transitionDrawable = new TransitionDrawable(layers);
                imageView.setImageDrawable(transitionDrawable);
                transitionDrawable.startTransition(250);
            } else {
                imageView.setImageDrawable(drawable);
            }
        }
    }
    
	private void setImage(RemoteControlClient remoteControl, Drawable drawable) {
    	Bitmap origBitmap = ((BitmapDrawable)drawable).getBitmap();
    	remoteControl.editMetadata(false)
    	.putBitmap(
    			RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK,
    			origBitmap.copy(origBitmap.getConfig(), true))
    	.apply();
    }

    private void setUnknownImage(View view, boolean large) {
        if (large) {
            setImage(view, largeUnknownImage, false);
        } else {
            if (view instanceof TextView) {
                ((TextView) view).setCompoundDrawablesWithIntrinsicBounds(R.drawable.unknown_album, 0, 0, 0);
            } else if (view instanceof ImageView) {
                ((ImageView) view).setImageResource(R.drawable.unknown_album);
            }
        }
    }
    
    private void setUnknownImage(RemoteControlClient remoteControl) {
        setImage(remoteControl, largeUnknownImage);
    }

    public void clear() {
        queue.clear();
    }

    @Override
    public void run() {
        while (true) {
            try {
                Task task = queue.take();
                task.execute();
            } catch (Throwable x) {
                Log.e(TAG, "Unexpected exception in ImageLoader.", x);
            }
        }
    }

    private Bitmap createReflectionEx(Bitmap originalImage) {

        int width = originalImage.getWidth();
        int height = originalImage.getHeight();

        // The gap we want between the reflection and the original image
        final int reflectionGap = 4;

        // This will not scale but will flip on the Y axis
        Matrix matrix = new Matrix();
        matrix.preScale(1, -1);

        // Create a Bitmap with the flip matrix applied to it.
        // We only want the bottom half of the image
        Bitmap reflectionImage = Bitmap.createBitmap(originalImage, 0, height / 2, width, height / 2, matrix, false);

        // Create a new bitmap with same width but taller to fit reflection
        Bitmap bitmapWithReflection = Bitmap.createBitmap(width, (height + height / 2), Bitmap.Config.ARGB_8888);

        // Create a new Canvas with the bitmap that's big enough for
        // the image plus gap plus reflection
        Canvas canvas = new Canvas(bitmapWithReflection);

        // Draw in the original image
        canvas.drawBitmap(originalImage, 0, 0, null);

        // Draw in the gap
        Paint defaultPaint = new Paint();
        canvas.drawRect(0, height, width, height + reflectionGap, defaultPaint);

        // Draw in the reflection
        canvas.drawBitmap(reflectionImage, 0, height + reflectionGap, null);

        // Create a shader that is a linear gradient that covers the reflection
        Paint paint = new Paint();
        LinearGradient shader = new LinearGradient(0, originalImage.getHeight(), 0, bitmapWithReflection.getHeight() + reflectionGap, 0x70000000, 0xff000000, Shader.TileMode.CLAMP);

        // Set the paint to use this shader (linear gradient)
        paint.setShader(shader);

        // Draw a rectangle using the paint with our linear gradient
        canvas.drawRect(0, height, width, bitmapWithReflection.getHeight() + reflectionGap, paint);

        return bitmapWithReflection;
    }
    
    
    private Bitmap createReflection(Bitmap originalImage) {

    //	int reflectionH = 80;
    	
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();

        // Height of reflection
        int reflectionHeight = height / 2;
        
        // The gap we want between the reflection and the original image
        final int reflectionGap = 4;

        // Create a new bitmap with same width but taller to fit reflection
        Bitmap bitmapWithReflection = Bitmap.createBitmap(width, (height + reflectionHeight), Bitmap.Config.ARGB_8888);
        
        //// ----
        
    	Bitmap reflection = Bitmap.createBitmap(width,reflectionHeight, Bitmap.Config.ARGB_8888);
    	Bitmap blurryBitmap = Bitmap.createBitmap(originalImage, 0, height - reflectionHeight, height, reflectionHeight);

    	// cheap and easy scaling algorithm; down-scale it, then
    	// upscale it. The filtering during the scale operations
    	// will blur the resulting image
    	blurryBitmap = Bitmap.createScaledBitmap(
    	Bitmap.createScaledBitmap(
    	blurryBitmap,blurryBitmap.getWidth() / 2,
    	blurryBitmap.getHeight() / 2, true),
    	blurryBitmap.getWidth(), blurryBitmap.getHeight(), true);
    	
    	// This shadier will hold a cropped, inverted,
    	// blurry version of the original image
    	BitmapShader bitmapShader = new BitmapShader(blurryBitmap, TileMode.CLAMP, TileMode.CLAMP);
    	Matrix invertMatrix = new Matrix();
    	invertMatrix.setScale(1f, -1f);
    	invertMatrix.preTranslate(0, -reflectionHeight);
    	bitmapShader.setLocalMatrix(invertMatrix);

    	// This shader holds an alpha gradient
    	Shader alphaGradient = new LinearGradient(0, 0, 0, reflectionHeight, 0x80ffffff, 0x00000000, TileMode.CLAMP);

    	// This shader combines the previous two, resulting in a
    	// blurred, fading reflection
    	ComposeShader compositor = new ComposeShader(bitmapShader, alphaGradient, PorterDuff.Mode.DST_IN);

    	Paint reflectionPaint = new Paint();
    	reflectionPaint.setShader(compositor);

    	// Draw the reflection into the bitmap that we will return
    	Canvas canvas = new Canvas(reflection);
    	canvas.drawRect(0, 0, reflection.getWidth(), reflection.getHeight(), reflectionPaint);

    	/// -----
    	
        // Create a new Canvas with the bitmap that's big enough for
        // the image plus gap plus reflection
        Canvas finalcanvas = new Canvas(bitmapWithReflection);

        // Draw in the original image
        finalcanvas.drawBitmap(originalImage, 0, 0, null);

        // Draw in the gap
        Paint defaultPaint = new Paint();
        finalcanvas.drawRect(0, height, width, height + reflectionGap, defaultPaint);

        // Draw in the reflection
        finalcanvas.drawBitmap(reflection, 0, height + reflectionGap, null);
    	
    	return bitmapWithReflection;
    }
    
    
	private class Task {
    	private final Context context;
        private final View view;
        private final RemoteControlClient remoteControl;
        private final MusicDirectory.Entry entry;
        private final Handler handler;
        private final int size;
        private final boolean reflection;
        private final boolean saveToFile;
        private final boolean crossfade;

        public Task(Context context, View view, RemoteControlClient remoteControl, MusicDirectory.Entry entry, int size, boolean reflection, boolean saveToFile, boolean crossfade) {
        	this.context = context;
        	this.view = view;
        	this.remoteControl = remoteControl;
            this.entry = entry;
            this.size = size;
            this.reflection = reflection;
            this.saveToFile = saveToFile;
            this.crossfade = crossfade;
            handler = new Handler();
        }

        public void execute() {
            try {
                MusicService musicService = MusicServiceFactory.getMusicService(context);
                Bitmap bitmap = musicService.getCoverArt(context, entry, size, saveToFile, null);

                if (reflection) {
                    bitmap = createReflection(bitmap);
                }

                final Drawable drawable = Util.createDrawableFromBitmap(context, bitmap);
                cache.put(getKey(entry.getCoverArt(), size), drawable);

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                    	if (view != null) {
                    		setImage(view, drawable, crossfade);
                    	} else if (remoteControl != null) {
                    		setImage(remoteControl, drawable);
                    	}
                    }
                });
            } catch (Throwable x) {
                Log.e(TAG, "Failed to download album art.", x);
            }
        }
    }
}
