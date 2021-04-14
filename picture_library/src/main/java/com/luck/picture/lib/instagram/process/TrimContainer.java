package com.luck.picture.lib.instagram.process;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.linkedin.android.litr.MediaTransformer;
import com.linkedin.android.litr.TrackTransform;
import com.linkedin.android.litr.TransformationListener;
import com.linkedin.android.litr.analytics.TrackTransformationInfo;
import com.linkedin.android.litr.codec.MediaCodecDecoder;
import com.linkedin.android.litr.codec.MediaCodecEncoder;
import com.linkedin.android.litr.exception.MediaTransformationException;
import com.linkedin.android.litr.filter.GlFilter;
import com.linkedin.android.litr.filter.GlFrameRenderFilter;
import com.linkedin.android.litr.filter.Transform;
import com.linkedin.android.litr.filter.video.gl.DefaultVideoFrameRenderFilter;
import com.linkedin.android.litr.io.MediaExtractorMediaSource;
import com.linkedin.android.litr.io.MediaMuxerMediaTarget;
import com.linkedin.android.litr.io.MediaRange;
import com.linkedin.android.litr.io.MediaSource;
import com.linkedin.android.litr.io.MediaTarget;
import com.linkedin.android.litr.render.GlVideoRenderer;
import com.linkedin.android.litr.utils.CodecUtils;
import com.luck.picture.lib.R;
import com.luck.picture.lib.config.PictureConfig;
import com.luck.picture.lib.config.PictureMimeType;
import com.luck.picture.lib.config.PictureSelectionConfig;
import com.luck.picture.lib.entity.LocalMedia;
import com.luck.picture.lib.instagram.AnimatorListenerImpl;
import com.luck.picture.lib.instagram.InsGallery;
import com.luck.picture.lib.instagram.InstagramPreviewContainer;
import com.luck.picture.lib.instagram.adapter.InstagramFrameItemDecoration;
import com.luck.picture.lib.instagram.adapter.VideoTrimmerAdapter;
import com.luck.picture.lib.thread.PictureThreadUtils;
import com.luck.picture.lib.tools.DateUtils;
import com.luck.picture.lib.tools.ScreenUtils;
import com.luck.picture.lib.tools.SdkVersionUtils;
import com.luck.picture.lib.tools.ToastUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * ================================================
 * Created by JessYan on 2020/6/24 17:07
 * <a href="mailto:jess.yan.effort@gmail.com">Contact me</a>
 * <a href="https://github.com/JessYanCoding">Follow me</a>
 * ================================================
 */
public class TrimContainer extends FrameLayout {
    private final int mPadding;
    private RecyclerView mRecyclerView;
    private PictureSelectionConfig mConfig;
    private LocalMedia mMedia;
    private VideoView mVideoView;
    private final VideoTrimmerAdapter mVideoTrimmerAdapter;
    private getAllFrameTask mFrameTask;
    private final VideoRulerView mVideoRulerView;
    private final RangeSeekBarView mRangeSeekBarView;
    private View mLeftShadow;
    private View mRightShadow;
    private View mIndicatorView;
    private int mScrollX;
    private int mThumbsCount;
    private ObjectAnimator mPauseAnim;
    private ObjectAnimator mIndicatorAnim;
    private float mIndicatorPosition;
    private LinearInterpolator mInterpolator;
    private boolean isRangeChange = true;
    private boolean mIsPreviewStart = true;
    private InstagramLoadingDialog mLoadingDialog;
    private Future<Void> mTranscodeFuture;
    private MediaTransformer mediaTransformer;

    private static final String KEY_ROTATION = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? MediaFormat.KEY_ROTATION
            : "rotation-degrees";

    public TrimContainer(@NonNull Context context, PictureSelectionConfig config, LocalMedia media, VideoView videoView, VideoPauseListener videoPauseListener) {
        super(context);
        mPadding = ScreenUtils.dip2px(context, 20);
        mRecyclerView = new RecyclerView(context);
        mConfig = config;
        mMedia = media;
        mVideoView = videoView;
        if (config.instagramSelectionConfig.getCurrentTheme() == InsGallery.THEME_STYLE_DEFAULT) {
            mRecyclerView.setBackgroundColor(Color.parseColor("#333333"));
        } else {
            mRecyclerView.setBackgroundColor(Color.BLACK);
        }
        mRecyclerView.setOverScrollMode(OVER_SCROLL_NEVER);
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.addItemDecoration(new InstagramFrameItemDecoration(mPadding));
        mRecyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        mVideoTrimmerAdapter = new VideoTrimmerAdapter();
        mRecyclerView.setAdapter(mVideoTrimmerAdapter);
        addView(mRecyclerView);
        ObjectAnimator.ofFloat(mRecyclerView, "translationX", ScreenUtils.getScreenWidth(context), 0).setDuration(300).start();
        mediaTransformer = new MediaTransformer(getContext().getApplicationContext());

        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {

            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                mScrollX += dx;
                mVideoRulerView.scrollBy(dx, 0);
                if (media.getDuration() > 60000 && dx != 0) {
                    changeRange(videoView, videoPauseListener, true);
                }
            }
        });

        mVideoRulerView = new VideoRulerView(context, media.getDuration());
        addView(mVideoRulerView);

        if (media.getDuration() > 60000) {
            mThumbsCount = Math.round(media.getDuration() / 7500f);
        } else if (media.getDuration() < 15000) {
            mThumbsCount = Math.round(media.getDuration() / 1875f);
        } else {
            mThumbsCount = 8;
        }

        mRangeSeekBarView = new RangeSeekBarView(context, 0, media.getDuration(), mThumbsCount);
        mRangeSeekBarView.setSelectedMinValue(0);
        mRangeSeekBarView.setSelectedMaxValue(media.getDuration());
        mRangeSeekBarView.setStartEndTime(0, media.getDuration());
        mRangeSeekBarView.setMinShootTime(3000L);
        mRangeSeekBarView.setNotifyWhileDragging(true);
        mRangeSeekBarView.setOnRangeSeekBarChangeListener((bar, minValue, maxValue, action, isMin, pressedThumb) -> changeRange(videoView, videoPauseListener, pressedThumb == RangeSeekBarView.Thumb.MIN));
        addView(mRangeSeekBarView);

        mLeftShadow = new View(context);
        mLeftShadow.setBackgroundColor(0xBF000000);
        addView(mLeftShadow);

        mRightShadow = new View(context);
        mRightShadow.setBackgroundColor(0xBF000000);
        addView(mRightShadow);

        mIndicatorView = new View(context);
        mIndicatorView.setBackgroundColor(Color.WHITE);
        addView(mIndicatorView);
        mIndicatorView.setVisibility(View.GONE);

        mVideoTrimmerAdapter.setItemCount(mThumbsCount);
        mFrameTask = new getAllFrameTask(context, media, mThumbsCount, 0, (int) media.getDuration(), new OnSingleBitmapListenerImpl(this));
        mFrameTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void changeRange(VideoView videoView, VideoPauseListener videoPauseListener, boolean isPreviewStart) {
        videoPauseListener.onChange();
        mIsPreviewStart = isPreviewStart;
        if (isPreviewStart) {
            videoView.seekTo((int) getStartTime());
        } else {
            videoView.seekTo((int) getEndTime());
        }
        if (videoView.isPlaying()) {
            videoPauseListener.onVideoPause();
        }
        isRangeChange = true;
        mIndicatorPosition = 0;
    }

    public void playVideo(boolean isPlay, VideoView videoView) {
        if (mPauseAnim != null && mPauseAnim.isRunning()) {
            mPauseAnim.cancel();
        }

        if (isPlay) {
            if (!mIsPreviewStart) {
                videoView.seekTo((int) getStartTime());
            }

            mIndicatorView.setVisibility(View.VISIBLE);
            mPauseAnim = ObjectAnimator.ofFloat(mIndicatorView, "alpha", 0, 1.0f).setDuration(200);

            if (isRangeChange) {
                isRangeChange = false;
                long startTime;
                if (mIndicatorPosition > 0) {
                    startTime = Math.round((mIndicatorPosition - ScreenUtils.dip2px(getContext(), 20)) / mVideoRulerView.getInterval() * 1000);
                } else {
                    startTime = getStartTime();
                }
                mIndicatorAnim = ObjectAnimator.ofFloat(mIndicatorView, "translationX", mIndicatorPosition > 0 ? mIndicatorPosition : mRangeSeekBarView.getStartLine() + ScreenUtils.dip2px(getContext(), 10),
                        mRangeSeekBarView.getEndLine() + ScreenUtils.dip2px(getContext(), 10)).setDuration(getEndTime() - startTime);
                mIndicatorAnim.addUpdateListener(animation -> mIndicatorPosition = (float) animation.getAnimatedValue());
                mIndicatorAnim.addListener(new AnimatorListenerImpl() {
                    @Override
                    public void onAnimationRepeat(Animator animation) {
                        if (videoView != null) {
                            videoView.seekTo((int) startTime);
                        }
                    }
                });
                mIndicatorAnim.setRepeatMode(ValueAnimator.RESTART);
                mIndicatorAnim.setRepeatCount(ValueAnimator.INFINITE);
                if (mInterpolator == null) {
                    mInterpolator = new LinearInterpolator();
                }
                mIndicatorAnim.setInterpolator(mInterpolator);
                mIndicatorAnim.start();
            } else {
                if (mIndicatorAnim != null && mIndicatorAnim.isPaused()) {
                    mIndicatorAnim.resume();
                }
            }
        } else {
            mPauseAnim = ObjectAnimator.ofFloat(mIndicatorView, "alpha", 1.0f, 0).setDuration(200);
            mPauseAnim.addListener(new AnimatorListenerImpl() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mIndicatorView.setVisibility(GONE);
                }
            });

            if (mIndicatorAnim != null && mIndicatorAnim.isRunning()) {
                mIndicatorAnim.pause();
            }
        }
        mPauseAnim.start();
    }

    public long getStartTime() {
        if (mThumbsCount < 8) {
            return Math.round(mRangeSeekBarView.getNormalizedMinValue() * mMedia.getDuration());
        } else {
            double min = mRangeSeekBarView.getNormalizedMinValue() * mRangeSeekBarView.getMeasuredWidth() + mScrollX;
            return Math.round((min > 0 ? min + ScreenUtils.dip2px(getContext(), 1) : min) / mVideoRulerView.getInterval() * 1000);
        }
    }

    public long getEndTime() {
        if (mThumbsCount < 8) {
            return Math.round(mRangeSeekBarView.getNormalizedMaxValue() * mMedia.getDuration());
        } else {
            double max = mRangeSeekBarView.getNormalizedMaxValue() * mVideoRulerView.getRangWidth() + mScrollX;
            return Math.round((max - ScreenUtils.dip2px(getContext(), 1)) / mVideoRulerView.getInterval() * 1000);
        }
    }

    public void cropVideo(InstagramMediaProcessActivity activity, boolean isAspectRatio) {
        showLoadingView(true);
        long startTime = getStartTime();
        long endTime = getEndTime();

        long startTimeUS = getStartTime() * 1000;
        long endTimeUS = getEndTime() * 1000;

        int sizeInMb = (int) (mMedia.getSize() / 1024 / 1024);

        Uri uri;
        if (SdkVersionUtils.checkedAndroid_Q() && PictureMimeType.isContent(mMedia.getPath())) {
            uri = Uri.parse(mMedia.getPath());
        } else {
            uri = Uri.fromFile(new File(mMedia.getPath()));
        }
        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
        mediaMetadataRetriever.setDataSource(getContext(), uri);
        int videoWidth = Integer.parseInt(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        int videoHeight = Integer.parseInt(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        int bitrate = Integer.parseInt(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));

        if (!mConfig.instagramSelectionConfig.isProcessVideo()) {
            if (mConfig.instagramSelectionConfig.isCropVideo()) {
                int videoRotation = Integer.parseInt(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                float instagramAspectRatio = InstagramPreviewContainer.getInstagramAspectRatio(
                        (videoRotation == 90 || videoRotation == 270) ? videoHeight : videoWidth,
                        (videoRotation == 90 || videoRotation == 270) ? videoWidth : videoHeight
                );

                if (isAspectRatio && instagramAspectRatio > 0) {
                    mMedia.setIsRatioOneToOne(false);
                } else if (!isAspectRatio) {
                    mMedia.setIsRatioOneToOne(true);
                }
            }
            mediaMetadataRetriever.release();
            mMedia.setDuration(endTime - startTime);
            mMedia.setStartTimeUS(startTimeUS);
            mMedia.setEndTimeUS(endTimeUS);
            List<LocalMedia> list = new ArrayList<>();
            list.add(mMedia);
            activity.showLoadingView(false);
            activity.setResult(Activity.RESULT_OK, new Intent().putParcelableArrayListExtra(PictureConfig.EXTRA_SELECT_LIST, (ArrayList<? extends Parcelable>) list));
            activity.finish();

        } else {
            File transcodeOutputFile;
            try {
                File outputDir = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES), "TrimVideos");
                //noinspection ResultOfMethodCallIgnored
                outputDir.mkdir();
                transcodeOutputFile = File.createTempFile(DateUtils.getCreateFileName("trim_"), ".mp4", outputDir);
            } catch (IOException e) {
                ToastUtils.s(getContext(), "Failed to create temporary file.");
                return;
            }

            int newWidth = videoWidth;
            int newHeight = videoHeight;
            int newWidthNonRatio = videoWidth;
            int newHeightNonRatio = videoHeight;
            int maxResolution = mConfig.maxVideoResolution;
            float ratio = (float) videoWidth / videoHeight;
            if (maxResolution > 0) {
                if (videoWidth > maxResolution || videoHeight > maxResolution) {
                    if (ratio > 1) {
                        newWidth = maxResolution;
                        newWidthNonRatio = newWidth;
                        newHeight = (int) (maxResolution / ratio);
                        newHeightNonRatio = newHeight;
                    } else {
                        newWidth = (int) (maxResolution * ratio);
                        newWidthNonRatio = newWidth;
                        newHeight = maxResolution;
                        newHeightNonRatio = newHeight;
                    }
                }
            }

            int minResolution = 720;
            // minimum resolution for mediacodec is 720
            if (newWidth < minResolution || newHeight < minResolution) {
                if (ratio > 1) {
                    newWidth = (int) (minResolution * ratio);
                    newWidthNonRatio = newWidth;
                    newHeight = minResolution;
                    newHeightNonRatio = newHeight;
                } else {
                    newWidth = minResolution;
                    newWidthNonRatio = newWidth;
                    newHeight = (int) (minResolution / ratio);
                    newHeightNonRatio = newHeight;
                }
            }

            if (!isAspectRatio) {
                if (newWidth > newHeight) {
                    if (videoHeight > newWidth) {
                        newWidth = maxResolution;
                        newHeight = maxResolution;
                    } else {
                        newWidth = videoHeight;
                        newHeight = videoHeight;
                    }
                } else {
                    if (videoWidth > newHeight) {
                        newWidth = maxResolution;
                        newHeight = maxResolution;
                    } else {
                        newWidth = videoWidth;
                        newHeight = videoWidth;
                    }
                }
            }

            try {
                MediaExtractor mediaExtractor = new MediaExtractor();
                mediaExtractor.setDataSource(getContext(), uri, null);
                List<MediaTrackFormat> tracks = new ArrayList<>(mediaExtractor.getTrackCount());

                for (int track = 0; track < mediaExtractor.getTrackCount(); track++) {
                    MediaFormat mediaFormat = mediaExtractor.getTrackFormat(track);
                    String mimeType = mediaFormat.getString(MediaFormat.KEY_MIME);
                    if (mimeType == null) {
                        continue;
                    }

                    if (mimeType.startsWith("video")) {
                        VideoTrackFormat videoTrack = new VideoTrackFormat(track, mimeType);
                        videoTrack.width = newWidth;
                        videoTrack.height = newHeight;
                        videoTrack.duration = getLong(mediaFormat, MediaFormat.KEY_DURATION);
                        videoTrack.frameRate = getInt(mediaFormat, MediaFormat.KEY_FRAME_RATE);
                        videoTrack.keyFrameInterval = getInt(mediaFormat, MediaFormat.KEY_I_FRAME_INTERVAL);
                        videoTrack.rotation = getInt(mediaFormat, KEY_ROTATION, 0);
                        videoTrack.bitrate = getInt(mediaFormat, MediaFormat.KEY_BIT_RATE);
                        tracks.add(videoTrack);
                    } else if (mimeType.startsWith("audio")) {
                        AudioTrackFormat audioTrack = new AudioTrackFormat(track, mimeType);
                        audioTrack.channelCount = getInt(mediaFormat, MediaFormat.KEY_CHANNEL_COUNT);
                        audioTrack.samplingRate = getInt(mediaFormat, MediaFormat.KEY_SAMPLE_RATE);
                        audioTrack.duration = getLong(mediaFormat, MediaFormat.KEY_DURATION);
                        audioTrack.bitrate = getInt(mediaFormat, MediaFormat.KEY_BIT_RATE);
                        tracks.add(audioTrack);
                    } else {
                        tracks.add(new GenericTrackFormat(track, mimeType));
                    }
                }

                int videoRotation = 0;
                for (MediaTrackFormat trackFormat : tracks) {
                    if (trackFormat.mimeType.startsWith("video")) {
                        videoRotation = ((VideoTrackFormat) trackFormat).rotation;
                        break;
                    }
                }
                TargetMedia targetMedia = new TargetMedia();
                targetMedia.setTargetFile(transcodeOutputFile);
                targetMedia.setTracks(tracks);

                MediaRange mediaRange = new MediaRange(startTimeUS, endTimeUS);
                MediaSource mediaSource = new MediaExtractorMediaSource(getContext(), uri, mediaRange);

                MediaTarget mediaTarget = new MediaMuxerMediaTarget(transcodeOutputFile.getPath(),
                        targetMedia.tracks.size(),
                        videoRotation,
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                List<TrackTransform> trackTransforms = new ArrayList<>(targetMedia.tracks.size());

                for (TargetTrack targetTrack : targetMedia.tracks) {
                    if (!targetTrack.shouldInclude) {
                        continue;
                    }

                    TrackTransform.Builder trackTransformBuilder = new TrackTransform.Builder(mediaSource,
                            targetTrack.sourceTrackIndex,
                            mediaTarget)
                            .setTargetTrack(trackTransforms.size())
                            .setEncoder(new MediaCodecEncoder())
                            .setDecoder(new MediaCodecDecoder());

                    if (targetTrack.format instanceof VideoTrackFormat) {
                        List<GlFilter> filters = new ArrayList<>();

                        if (!isAspectRatio) {
                            Transform transform;
                            if (videoRotation == 0 || videoRotation == 180) {
                                // landscape
                                transform = new Transform(new PointF((float) newWidthNonRatio / newHeightNonRatio, 1.0f), new PointF(0.5f, 0.5f), 0);
                            } else {
                                // portrait
                                transform = new Transform(new PointF(1.0f, (float) newWidthNonRatio / newHeightNonRatio), new PointF(0.5f, 0.5f), 0);
                            }
                            GlFrameRenderFilter frameRenderFilter = new DefaultVideoFrameRenderFilter(transform);
                            filters.add(frameRenderFilter);
                        }
                        trackTransformBuilder.setRenderer(new GlVideoRenderer(filters));
                    }
                    MediaFormat mediaFormat = createMediaFormat(targetTrack);
                    if (mediaFormat != null && targetTrack.format.mimeType.startsWith("video")) {
                        if (mConfig.minFileSizeForCompression > 0 && sizeInMb < mConfig.minFileSizeForCompression) {
                            // set original bitrate
                            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                        }
                        mediaFormat.setInteger(MediaFormat.KEY_WIDTH, newWidth);
                        mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, newHeight);
                    }
                    trackTransformBuilder.setTargetFormat(mediaFormat);
                    trackTransforms.add(trackTransformBuilder.build());
                }

                mediaTransformer.transform("99999",
                        trackTransforms,
                        new TransformationListenerImpl(this, activity, startTime, endTime, transcodeOutputFile, "99999"),
                        MediaTransformer.GRANULARITY_DEFAULT);

            } catch (IOException | MediaTransformationException e) {
                e.printStackTrace();
            }
        }
    }

    private void showLoadingView(boolean isShow) {
        if (((Activity) getContext()).isFinishing()) {
            return;
        }
        if (isShow) {
            if (mLoadingDialog == null) {
                mLoadingDialog = new InstagramLoadingDialog(getContext());
                mLoadingDialog.setOnCancelListener(dialog -> {
                    if (mTranscodeFuture != null) {
                        mTranscodeFuture.cancel(true);
                    }
                    if (mediaTransformer != null) {
                        mediaTransformer.cancel("99999");
                    }
                });
            }
            if (mLoadingDialog.isShowing()) {
                mLoadingDialog.dismiss();
            }
            mLoadingDialog.updateProgress(0);
            mLoadingDialog.show();
        } else {
            if (mLoadingDialog != null
                    && mLoadingDialog.isShowing()) {
                mLoadingDialog.dismiss();
            }
        }
    }

    public void trimVideo(InstagramMediaProcessActivity activity, CountDownLatch count) {
        activity.showLoadingView(true);

        long startTime = getStartTime();
        long endTime = getEndTime();

        PictureThreadUtils.executeByIo(new PictureThreadUtils.SimpleTask<File>() {

            @Override
            public File doInBackground() {
                Uri inputUri;
                if (SdkVersionUtils.checkedAndroid_Q() && PictureMimeType.isContent(mMedia.getPath())) {
                    inputUri = Uri.parse(mMedia.getPath());
                } else {
                    inputUri = Uri.fromFile(new File(mMedia.getPath()));
                }

                try {
                    File outputDir = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES), "TrimVideos");
                    outputDir.mkdir();
                    File outputFile = File.createTempFile(DateUtils.getCreateFileName("trim_"), ".mp4", outputDir);

                    ParcelFileDescriptor parcelFileDescriptor = getContext().getContentResolver().openFileDescriptor(inputUri, "r");
                    if (parcelFileDescriptor != null) {
                        FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
//                        boolean succeeded = VideoClipUtils.trimUsingMp4Parser(fileDescriptor, outputFile.getAbsolutePath(), startTime, endTime);
//                        if (!succeeded) {
                        boolean succeeded = VideoClipUtils.genVideoUsingMuxer(fileDescriptor, outputFile.getAbsolutePath(), startTime, endTime, true, true);
//                        }
                        if (succeeded) {
                            count.countDown();
                            try {
                                count.await(1500, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            return outputFile;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return null;
            }

            @Override
            public void onSuccess(File result) {
                if (result != null) {
                    mMedia.setDuration(endTime - startTime);
                    mMedia.setPath(result.getAbsolutePath());
                    mMedia.setAndroidQToPath(SdkVersionUtils.checkedAndroid_Q() ? result.getAbsolutePath() : mMedia.getAndroidQToPath());
                    List<LocalMedia> list = new ArrayList<>();
                    list.add(mMedia);
                    activity.showLoadingView(false);
                    activity.setResult(Activity.RESULT_OK, new Intent().putParcelableArrayListExtra(PictureConfig.EXTRA_SELECT_LIST, (ArrayList<? extends Parcelable>) list));
                    activity.finish();
                } else {
                    ToastUtils.s(getContext(), getContext().getString(R.string.video_clip_failed));
                }
            }
        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        mRecyclerView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ScreenUtils.dip2px(getContext(), 90), MeasureSpec.EXACTLY));
        mRangeSeekBarView.measure(MeasureSpec.makeMeasureSpec(width - ScreenUtils.dip2px(getContext(), 20), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ScreenUtils.dip2px(getContext(), 90), MeasureSpec.EXACTLY));
        mLeftShadow.measure(MeasureSpec.makeMeasureSpec(ScreenUtils.dip2px(getContext(), 10), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ScreenUtils.dip2px(getContext(), 90), MeasureSpec.EXACTLY));
        mRightShadow.measure(MeasureSpec.makeMeasureSpec(ScreenUtils.dip2px(getContext(), 10), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ScreenUtils.dip2px(getContext(), 90), MeasureSpec.EXACTLY));
        mVideoRulerView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height - ScreenUtils.dip2px(getContext(), 90), MeasureSpec.EXACTLY));
        if (mIndicatorView.getVisibility() == View.VISIBLE) {
            mIndicatorView.measure(MeasureSpec.makeMeasureSpec(ScreenUtils.dip2px(getContext(), 2), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ScreenUtils.dip2px(getContext(), 90), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int viewTop = 0;
        int viewLeft = 0;
        mRecyclerView.layout(viewLeft, viewTop, viewLeft + mRecyclerView.getMeasuredWidth(), viewTop + mRecyclerView.getMeasuredHeight());

        mLeftShadow.layout(viewLeft, viewTop, viewLeft + mLeftShadow.getMeasuredWidth(), viewTop + mLeftShadow.getMeasuredHeight());

        viewLeft = getMeasuredWidth() - ScreenUtils.dip2px(getContext(), 10);
        mRightShadow.layout(viewLeft, viewTop, viewLeft + mRightShadow.getMeasuredWidth(), viewTop + mRightShadow.getMeasuredHeight());

        viewLeft = ScreenUtils.dip2px(getContext(), 20) - ScreenUtils.dip2px(getContext(), 10);
        mRangeSeekBarView.layout(viewLeft, viewTop, viewLeft + mRangeSeekBarView.getMeasuredWidth(), viewTop + mRangeSeekBarView.getMeasuredHeight());

        viewLeft = 0;
        viewTop += mRecyclerView.getMeasuredHeight();
        mVideoRulerView.layout(viewLeft, viewTop, viewLeft + mVideoRulerView.getMeasuredWidth(), viewTop + mVideoRulerView.getMeasuredHeight());

        viewTop = 0;
        if (mIndicatorView.getVisibility() == View.VISIBLE) {
            mIndicatorView.layout(viewLeft, viewTop, viewLeft + mIndicatorView.getMeasuredWidth(), viewTop + mIndicatorView.getMeasuredHeight());
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        Rect rect = new Rect();
        mVideoRulerView.getHitRect(rect);
        if (rect.contains((int) (ev.getX()), (int) (ev.getY()))) {
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mRecyclerView.onTouchEvent(event);
        return true;
    }

    public void onResume() {
        if (mVideoView != null) {
            mVideoView.seekTo((int) getStartTime());
        }
    }

    public void onPause() {
        if (mIndicatorAnim != null && mIndicatorAnim.isRunning()) {
            mIndicatorAnim.cancel();
        }
        resetStartLine();
    }

    public void resetStartLine() {
        isRangeChange = true;
        mIndicatorPosition = 0;
        mIndicatorView.setVisibility(GONE);
    }

    public void onDestroy() {
        if (mFrameTask != null) {
            mFrameTask.setStop(true);
            mFrameTask.cancel(true);
            mFrameTask = null;
        }
        if (mTranscodeFuture != null) {
            mTranscodeFuture.cancel(true);
            mTranscodeFuture = null;
        }
        if (mediaTransformer != null) {
            mediaTransformer.cancel("99999");
            mediaTransformer.release();
            mediaTransformer = null;
        }
    }

    public static class OnSingleBitmapListenerImpl implements getAllFrameTask.OnSingleBitmapListener {
        private WeakReference<TrimContainer> mContainerWeakReference;

        public OnSingleBitmapListenerImpl(TrimContainer container) {
            mContainerWeakReference = new WeakReference<>(container);
        }

        @Override
        public void onSingleBitmapComplete(Bitmap bitmap) {
            TrimContainer container = mContainerWeakReference.get();
            if (container != null) {
                container.post(new RunnableImpl(container.mVideoTrimmerAdapter, bitmap));
            }
        }

        public static class RunnableImpl implements Runnable {
            private WeakReference<VideoTrimmerAdapter> mAdapterWeakReference;
            private Bitmap mBitmap;

            public RunnableImpl(VideoTrimmerAdapter adapter, Bitmap bitmap) {
                mAdapterWeakReference = new WeakReference<>(adapter);
                mBitmap = bitmap;
            }

            @Override
            public void run() {
                VideoTrimmerAdapter adapter = mAdapterWeakReference.get();
                if (adapter != null) {
                    adapter.addBitmaps(mBitmap);
                }
            }
        }
    }

    public interface VideoPauseListener {
        void onChange();

        void onVideoPause();
    }

    private static class TransformationListenerImpl implements TransformationListener {
        private WeakReference<TrimContainer> mContainerWeakReference;
        private WeakReference<InstagramMediaProcessActivity> mActivityWeakReference;
        private long mStartTime;
        private long mEndTime;
        private File mTranscodeOutputFile;
        private final String requestId;

        public TransformationListenerImpl(TrimContainer container, InstagramMediaProcessActivity activity, long startTime, long endTime, File transcodeOutputFile, String requestId) {
            mContainerWeakReference = new WeakReference<>(container);
            mActivityWeakReference = new WeakReference<>(activity);
            mStartTime = startTime;
            mEndTime = endTime;
            mTranscodeOutputFile = transcodeOutputFile;
            this.requestId = requestId;
        }

        @Override
        public void onStarted(@NonNull String id) {

        }

        @Override
        public void onProgress(@NonNull String id, float progress) {
            TrimContainer trimContainer = mContainerWeakReference.get();
            if (trimContainer == null) {
                return;
            }
            if (trimContainer.mLoadingDialog != null
                    && trimContainer.mLoadingDialog.isShowing()) {
                trimContainer.mLoadingDialog.updateProgress(progress);
            }
        }

        @Override
        public void onCompleted(@NonNull String id, @Nullable List<TrackTransformationInfo> trackTransformationInfos) {
            if (TextUtils.equals(requestId, id)) {
                TrimContainer trimContainer = mContainerWeakReference.get();
                InstagramMediaProcessActivity activity = mActivityWeakReference.get();
                if (trimContainer == null || activity == null) {
                    return;
                }
                trimContainer.showLoadingView(false);

                trimContainer.mMedia.setDuration(mEndTime - mStartTime);
                trimContainer.mMedia.setPath(mTranscodeOutputFile.getAbsolutePath());
                trimContainer.mMedia.setAndroidQToPath(SdkVersionUtils.checkedAndroid_Q() ? mTranscodeOutputFile.getAbsolutePath() : trimContainer.mMedia.getAndroidQToPath());
                List<LocalMedia> list = new ArrayList<>();
                list.add(trimContainer.mMedia);
                activity.setResult(Activity.RESULT_OK, new Intent().putParcelableArrayListExtra(PictureConfig.EXTRA_SELECT_LIST, (ArrayList<? extends Parcelable>) list));
                activity.finish();
                trimContainer.showLoadingView(false);
            }
        }

        @Override
        public void onCancelled(@NonNull String id, @Nullable List<TrackTransformationInfo> trackTransformationInfos) {
            TrimContainer trimContainer = mContainerWeakReference.get();
            if (trimContainer == null) {
                return;
            }
            trimContainer.showLoadingView(false);
        }

        @Override
        public void onError(@NonNull String id, @Nullable Throwable cause, @Nullable List<TrackTransformationInfo> trackTransformationInfos) {
            TrimContainer trimContainer = mContainerWeakReference.get();
            if (trimContainer == null) {
                return;
            }
            if (cause != null) {
                cause.printStackTrace();
            }
            ToastUtils.s(trimContainer.getContext(), trimContainer.getContext().getString(R.string.video_clip_failed));
            trimContainer.showLoadingView(false);
        }
    }

    private int getInt(@NonNull MediaFormat mediaFormat, @NonNull String key) {
        return getInt(mediaFormat, key, -1);
    }

    private int getInt(@NonNull MediaFormat mediaFormat, @NonNull String key, int defaultValue) {
        if (mediaFormat.containsKey(key)) {
            return mediaFormat.getInteger(key);
        }
        return defaultValue;
    }

    private long getLong(@NonNull MediaFormat mediaFormat, @NonNull String key) {
        if (mediaFormat.containsKey(key)) {
            return mediaFormat.getLong(key);
        }
        return -1;
    }

    @Nullable
    private MediaFormat createMediaFormat(@Nullable TargetTrack targetTrack) {
        MediaFormat mediaFormat = null;
        if (targetTrack != null && targetTrack.format != null) {
            mediaFormat = new MediaFormat();
            if (targetTrack.format.mimeType.startsWith("video")) {
                VideoTrackFormat trackFormat = (VideoTrackFormat) targetTrack.format;
                String mimeType = CodecUtils.MIME_TYPE_VIDEO_AVC;
                mediaFormat.setString(MediaFormat.KEY_MIME, mimeType);
                mediaFormat.setInteger(MediaFormat.KEY_WIDTH, trackFormat.width);
                mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, trackFormat.height);
                mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, trackFormat.bitrate);
                mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, trackFormat.keyFrameInterval);
                mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, trackFormat.frameRate);
            } else if (targetTrack.format.mimeType.startsWith("audio")) {
                AudioTrackFormat trackFormat = (AudioTrackFormat) targetTrack.format;
                mediaFormat.setString(MediaFormat.KEY_MIME, trackFormat.mimeType);
                mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, trackFormat.channelCount);
                mediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, trackFormat.samplingRate);
                mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, trackFormat.bitrate);
            }
        }

        return mediaFormat;
    }
}

class MediaTrackFormat {

    public int index;
    public String mimeType;

    MediaTrackFormat(int index, @NonNull String mimeType) {
        this.index = index;
        this.mimeType = mimeType;
    }

    MediaTrackFormat(@NonNull MediaTrackFormat mediaTrackFormat) {
        this.index = mediaTrackFormat.index;
        this.mimeType = mediaTrackFormat.mimeType;
    }
}

class VideoTrackFormat extends MediaTrackFormat {

    public int width;
    public int height;
    public int bitrate;
    public int frameRate;
    public int keyFrameInterval;
    public long duration;
    public int rotation;

    public VideoTrackFormat(int index, @NonNull String mimeType) {
        super(index, mimeType);
    }

    public VideoTrackFormat(@NonNull VideoTrackFormat videoTrackFormat) {
        super(videoTrackFormat);
        this.width = videoTrackFormat.width;
        this.height = videoTrackFormat.height;
        this.bitrate = videoTrackFormat.bitrate;
        this.frameRate = videoTrackFormat.frameRate;
        this.keyFrameInterval = videoTrackFormat.keyFrameInterval;
        this.duration = videoTrackFormat.duration;
        this.rotation = videoTrackFormat.rotation;
    }
}

class AudioTrackFormat extends MediaTrackFormat {

    public int channelCount;
    public int samplingRate;
    public int bitrate;
    public long duration;

    public AudioTrackFormat(int index, @NonNull String mimeType) {
        super(index, mimeType);
    }

    public AudioTrackFormat(@NonNull AudioTrackFormat audioTrackFormat) {
        super(audioTrackFormat);
        this.channelCount = audioTrackFormat.channelCount;
        this.samplingRate = audioTrackFormat.samplingRate;
        this.bitrate = audioTrackFormat.bitrate;
        this.duration = audioTrackFormat.duration;
    }
}

class GenericTrackFormat extends MediaTrackFormat {

    public GenericTrackFormat(int index, @NonNull String mimeType) {
        super(index, mimeType);
    }
}

class TargetMedia {

    public static final int DEFAULT_VIDEO_WIDTH = 1280;
    public static final int DEFAULT_VIDEO_HEIGHT = 720;
    public static final int DEFAULT_VIDEO_BITRATE = 2500000;
    public static final int DEFAULT_KEY_FRAME_INTERVAL = 5;
    public static final int DEFAULT_AUDIO_BITRATE = 128000;

    public File targetFile;
    public List<TargetTrack> tracks = new ArrayList<>();
    public Uri backgroundImageUri;
    public GlFilter filter;

    public void setTracks(@NonNull List<MediaTrackFormat> sourceTracks) {
        tracks = new ArrayList<>(sourceTracks.size());
        for (MediaTrackFormat sourceTrackFormat : sourceTracks) {
            TargetTrack targetTrack;
            if (sourceTrackFormat instanceof VideoTrackFormat) {
                VideoTrackFormat trackFormat = new VideoTrackFormat((VideoTrackFormat) sourceTrackFormat);
                trackFormat.width = DEFAULT_VIDEO_WIDTH;
                trackFormat.height = DEFAULT_VIDEO_HEIGHT;
                trackFormat.bitrate = DEFAULT_VIDEO_BITRATE;
                trackFormat.keyFrameInterval = DEFAULT_KEY_FRAME_INTERVAL;
                targetTrack = new TargetVideoTrack(sourceTrackFormat.index,
                        true,
                        false,
                        trackFormat);
            } else if (sourceTrackFormat instanceof AudioTrackFormat) {
                AudioTrackFormat trackFormat = new AudioTrackFormat((AudioTrackFormat) sourceTrackFormat);
                trackFormat.bitrate = DEFAULT_AUDIO_BITRATE;
                targetTrack = new TargetAudioTrack(sourceTrackFormat.index,
                        true,
                        false,
                        trackFormat);
            } else {
                targetTrack = new TargetTrack(sourceTrackFormat.index,
                        true,
                        false,
                        new MediaTrackFormat(sourceTrackFormat));
            }
            tracks.add(targetTrack);
        }
    }

    public void setTargetFile(@NonNull File targetFile) {
        this.targetFile = targetFile;
    }

    public int getIncludedTrackCount() {
        int trackCount = 0;
        for (TargetTrack track : tracks) {
            if (track.shouldInclude) {
                trackCount++;
            }
        }
        return trackCount;
    }

    public void setOverlayImageUri(@NonNull Uri overlayImageUri) {
        for (TargetTrack targetTrack : tracks) {
            if (targetTrack instanceof TargetVideoTrack) {
                ((TargetVideoTrack) targetTrack).overlay = overlayImageUri;
            }
        }
    }

    @Nullable
    public Uri getVideoOverlay() {
        for (TargetTrack targetTrack : tracks) {
            if ((targetTrack instanceof TargetVideoTrack) && ((TargetVideoTrack) targetTrack).overlay != null) {
                return ((TargetVideoTrack) targetTrack).overlay;
            }
        }
        return null;
    }

}

class TargetTrack {
    public int sourceTrackIndex;
    public boolean shouldInclude;
    public boolean shouldTranscode;
    public MediaTrackFormat format;

    public TargetTrack(int sourceTrackIndex, boolean shouldInclude, boolean shouldTranscode, @NonNull MediaTrackFormat format) {
        this.sourceTrackIndex = sourceTrackIndex;
        this.shouldInclude = shouldInclude;
        this.shouldTranscode = shouldTranscode;
        this.format = format;
    }
}

class TargetVideoTrack extends TargetTrack {

    public boolean shouldApplyOverlay;
    public Uri overlay;

    public TargetVideoTrack(int sourceTrackIndex,
                            boolean shouldInclude,
                            boolean shouldTranscode,
                            VideoTrackFormat format) {
        super(sourceTrackIndex, shouldInclude, shouldTranscode, format);
    }

    public VideoTrackFormat getTrackFormat() {
        return (VideoTrackFormat) format;
    }
}

class TargetAudioTrack extends TargetTrack {
    public TargetAudioTrack(int sourceTrackIndex,
                            boolean shouldInclude,
                            boolean shouldTranscode,
                            AudioTrackFormat format) {
        super(sourceTrackIndex, shouldInclude, shouldTranscode, format);
    }

    public AudioTrackFormat getTrackFormat() {
        return (AudioTrackFormat) format;
    }
}
