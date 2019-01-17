package expo.modules.imagemanipulator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

import com.facebook.common.executors.CallerThreadExecutor;
import com.facebook.common.references.CloseableReference;
import com.facebook.datasource.DataSource;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.common.RotationOptions;
import com.facebook.imagepipeline.datasource.BaseBitmapDataSubscriber;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;

import expo.core.ExportedModule;
import expo.core.ModuleRegistry;
import expo.core.Promise;
import expo.core.interfaces.ExpoMethod;
import expo.core.interfaces.ModuleRegistryConsumer;
import expo.core.utilieties.FileUtilities;
import expo.modules.imagemanipulator.arguments.Action;
import expo.modules.imagemanipulator.arguments.ActionCrop;
import expo.modules.imagemanipulator.arguments.ActionFlip;
import expo.modules.imagemanipulator.arguments.ActionResize;
import expo.modules.imagemanipulator.arguments.SaveOptions;

public class ImageManipulatorModule extends ExportedModule implements ModuleRegistryConsumer {
  private static final String TAG = "ExpoImageManipulator";
  private static final String ERROR_TAG = "E_IMAGe_MANIPULATOR";

  public ImageManipulatorModule(Context context) {
    super(context);
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public void setModuleRegistry(ModuleRegistry moduleRegistry) {
  }

  @ExpoMethod
  public void manipulateAsync(final String uri, final ArrayList<Object> actions, final Map<String, Object> saveOptions, final Promise promise) {
    if (uri == null || uri.length() == 0) {
      promise.reject(ERROR_TAG + "_INVALID_ARG", "Uri passed to ImageManipulator cannot be empty!");
      return;
    }

    final SaveOptions manipulatorSaveOptions;
    final ArrayList<Action> manipulatorActions = new ArrayList<>();
    try {
      manipulatorSaveOptions = SaveOptions.fromMap(saveOptions);
      for (Object action : actions) {
        manipulatorActions.add(Action.fromObject(action));
      }
    } catch (IllegalArgumentException e) {
      promise.reject(ERROR_TAG + "_INVALID_ARG", e);
      return;
    }


    ImageRequest imageRequest =
        ImageRequestBuilder
            .newBuilderWithSource(Uri.parse(uri))
            .setRotationOptions(RotationOptions.autoRotate())
            .build();

    final DataSource<CloseableReference<CloseableImage>> dataSource
        = Fresco.getImagePipeline().fetchDecodedImage(imageRequest, getContext());
    dataSource.subscribe(new BaseBitmapDataSubscriber() {
                           @Override
                           public void onNewResultImpl(Bitmap bitmap) {
                             if (bitmap != null) {
                               processBitmapWithActions(bitmap, manipulatorActions, manipulatorSaveOptions, promise);
                             } else {
                               onFailureImpl(dataSource);
                             }
                           }

                           @Override
                           public void onFailureImpl(DataSource dataSource) {
                             // No cleanup required here.
                             String basicMessage = "Could not get decoded bitmap of " + uri;
                             if (dataSource.getFailureCause() != null) {
                               promise.reject(ERROR_TAG + "_DECODE",
                                   basicMessage + ": " + dataSource.getFailureCause().toString(), dataSource.getFailureCause());
                             } else {
                               promise.reject(ERROR_TAG + "_DECODE", basicMessage + ".");
                             }
                           }
                         },
        CallerThreadExecutor.getInstance()
    );
  }

  private Bitmap resizeBitmap(Bitmap bitmap, ActionResize resize) {
    float imageRatio = (float) bitmap.getWidth() / bitmap.getHeight();
    int requestedWidth = resize.getWidth() != 0
        ? resize.getWidth()
        : resize.getHeight() != 0
          ? (int) (resize.getHeight() * imageRatio)
          : 0;
    int requestedHeight = resize.getHeight() != 0
        ? resize.getHeight()
        : resize.getWidth() != 0
          ? (int) (resize.getWidth() / imageRatio)
          : 0;
    return Bitmap.createScaledBitmap(bitmap, requestedWidth, requestedHeight, true);
  }

  private Bitmap rotateBitmap(Bitmap bitmap, Integer rotation) {
    Matrix rotationMatrix = new Matrix();
    rotationMatrix.postRotate(rotation);
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotationMatrix, true);
  }

  private Bitmap flipBitmap(Bitmap bmp, ActionFlip flip) {
    Matrix rotationMatrix = new Matrix();
    if (flip.isHorizontal()) {
      rotationMatrix.postScale(-1, 1);
    } else if (flip.isVertical()) {
      rotationMatrix.postScale(1, -1);
    }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), rotationMatrix, true);
  }

  private Bitmap cropBitmap(Bitmap bitmap, ActionCrop crop) throws IllegalArgumentException {
    if (crop.getOriginX() > bitmap.getWidth()
        || crop.getOriginX() > bitmap.getHeight()
        || crop.getOriginX() + crop.getWidth() > bitmap.getWidth()
        || crop.getOriginY() + crop.getHeight() > bitmap.getHeight()
    ) {
      throw new IllegalArgumentException("Invalid crop options has been passed. Please make sure the requested crop rectangle is inside source image.");
    }
    return Bitmap.createBitmap(bitmap, crop.getOriginX(), crop.getOriginY(), crop.getWidth(), crop.getHeight());
  }

  private void processBitmapWithActions(Bitmap bitmap, final ArrayList<Action> actions, final SaveOptions saveOptions, Promise promise) {
    for (Action action : actions) {
      if (action.getResize() != null) {
        bitmap = resizeBitmap(bitmap, action.getResize());
      } else if (action.getRotate() != null) {
        bitmap = rotateBitmap(bitmap, action.getRotate());
      } else if (action.getFlip() != null) {
        bitmap = flipBitmap(bitmap, action.getFlip());
      } else if (action.getCrop() != null) {
        try {
          bitmap = cropBitmap(bitmap, action.getCrop());
        } catch (IllegalArgumentException e) {
          promise.reject(ERROR_TAG + "_CROP_DATA", e);
          return;
        }
      }
    }

    int compression = (int) (saveOptions.getCompress() * 100);

    FileOutputStream out = null;
    ByteArrayOutputStream byteOut = null;
    String path = null;
    String base64String = null;
    try {
      path = FileUtilities.generateOutputPath(getContext().getCacheDir(), "ImageManipulator", saveOptions.getFormat().getFileExtension());
      out = new FileOutputStream(path);
      bitmap.compress(saveOptions.getFormat().getCompressFormat(), compression, out);

      if (saveOptions.hasBase64()) {
        byteOut = new ByteArrayOutputStream();
        bitmap.compress(saveOptions.getFormat().getCompressFormat(), compression, byteOut);
        base64String = Base64.encodeToString(byteOut.toByteArray(), Base64.DEFAULT);
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      try {
        if (out != null) {
          out.close();
        }
        if (byteOut != null) {
          byteOut.close();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    Bundle response = new Bundle();
    response.putString("uri", Uri.fromFile(new File(path)).toString());
    response.putInt("width", bitmap.getWidth());
    response.putInt("height", bitmap.getHeight());
    if (saveOptions.hasBase64()) {
      response.putString("base64", base64String);
    }
    promise.resolve(response);
  }
}
