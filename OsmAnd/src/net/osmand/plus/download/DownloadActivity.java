package net.osmand.plus.download;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.StatFs;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.v4.app.ActivityCompat.OnRequestPermissionsResultCallback;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.Space;
import android.support.v7.app.AlertDialog;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import net.osmand.IProgress;
import net.osmand.IndexConstants;
import net.osmand.PlatformUtil;
import net.osmand.access.AccessibilityAssistant;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.map.WorldRegion;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.Version;
import net.osmand.plus.activities.LocalIndexInfo;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.activities.TabActivity;
import net.osmand.plus.base.BasicProgressAsyncTask;
import net.osmand.plus.base.BottomSheetDialogFragment;
import net.osmand.plus.chooseplan.ChoosePlanDialogFragment;
import net.osmand.plus.download.DownloadIndexesThread.DownloadEvents;
import net.osmand.plus.download.ui.ActiveDownloadsDialogFragment;
import net.osmand.plus.download.ui.DownloadResourceGroupFragment;
import net.osmand.plus.download.ui.LocalIndexesFragment;
import net.osmand.plus.download.ui.SearchDialogFragment;
import net.osmand.plus.download.ui.UpdatesIndexFragment;
import net.osmand.plus.helpers.FileNameTranslationHelper;
import net.osmand.plus.inapp.InAppPurchaseHelper;
import net.osmand.plus.inapp.InAppPurchaseHelper.InAppPurchaseListener;
import net.osmand.plus.inapp.InAppPurchaseHelper.InAppPurchaseTaskType;
import net.osmand.plus.openseamapsplugin.NauticalMapsPlugin;
import net.osmand.plus.srtmplugin.SRTMPlugin;
import net.osmand.plus.views.controls.PagerSlidingTabStrip;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadActivity extends AbstractDownloadActivity implements DownloadEvents,
		OnRequestPermissionsResultCallback {
	private static final Log LOG = PlatformUtil.getLog(DownloadActivity.class);

	public static final int UPDATES_TAB_NUMBER = 2;
	public static final int LOCAL_TAB_NUMBER = 1;
	public static final int DOWNLOAD_TAB_NUMBER = 0;
	public static final int PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 0;


	private List<LocalIndexInfo> localIndexInfos = new ArrayList<>();

	List<TabActivity.TabItem> mTabs = new ArrayList<TabActivity.TabItem>();
	public static final String FILTER_KEY = "filter";
	public static final String FILTER_CAT = "filter_cat";
	public static final String FILTER_GROUP = "filter_group";

	public static final String TAB_TO_OPEN = "Tab_to_open";
	public static final String LOCAL_TAB = "local";
	public static final String DOWNLOAD_TAB = "download";
	public static final String UPDATES_TAB = "updates";
	public static final String REGION_TO_SEARCH = "search_region";
	public static final MessageFormat formatGb = new MessageFormat("{0, number,#.##} GB", Locale.US);
	public static final MessageFormat formatMb = new MessageFormat("{0, number,##.#} MB", Locale.US);
	public static final MessageFormat formatKb = new MessageFormat("{0, number,##.#} kB", Locale.US);
	private static boolean SUGGESTED_TO_DOWNLOAD_BASEMAP = false;

	private BannerAndDownloadFreeVersion visibleBanner;
	private ViewPager viewPager;
	private AccessibilityAssistant accessibilityAssistant;
	private String filter;
	private String filterCat;
	private String filterGroup;
	protected Set<WeakReference<Fragment>> fragSet = new HashSet<>();
	private DownloadIndexesThread downloadThread;
	protected WorldRegion downloadItem;
	protected String downloadTargetFileName;

	private boolean srtmDisabled;
	private boolean srtmNeedsInstallation;
	private boolean nauticalPluginDisabled;
	private boolean freeVersion;

	private ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		getMyApplication().applyTheme(this);
		super.onCreate(savedInstanceState);
		getMyApplication().fetchRemoteParams();
		downloadThread = getMyApplication().getDownloadThread();
		DownloadResources indexes = getDownloadThread().getIndexes();
		if (!indexes.isDownloadedFromInternet) {
			getDownloadThread().runReloadIndexFiles();
		}
		accessibilityAssistant = new AccessibilityAssistant(this);

		setContentView(R.layout.download);
		String region = getIntent() == null ? "" : getIntent().getStringExtra(REGION_TO_SEARCH);
		if (region != null && !region.isEmpty()) {
			showDialog(this, SearchDialogFragment.createInstance(region));
		}
		//noinspection ConstantConditions
		getSupportActionBar().setTitle(R.string.shared_string_map);
		final View downloadProgressLayout = findViewById(R.id.downloadProgressLayout);
		downloadProgressLayout.setVisibility(View.VISIBLE);
		updateDescriptionTextWithSize(this, downloadProgressLayout);
		int currentTab = DOWNLOAD_TAB_NUMBER;
		String tab = getIntent() == null || getIntent().getExtras() == null ? null : getIntent().getExtras().getString(TAB_TO_OPEN);
		if (tab != null) {
			if (tab.equals(DOWNLOAD_TAB)) {
				currentTab = DOWNLOAD_TAB_NUMBER;
			} else if (tab.equals(LOCAL_TAB)) {
				currentTab = LOCAL_TAB_NUMBER;
			} else if (tab.equals(UPDATES_TAB)) {
				currentTab = UPDATES_TAB_NUMBER;
			}
		}

		viewPager = (ViewPager) findViewById(R.id.pager);
		PagerSlidingTabStrip mSlidingTabLayout = (PagerSlidingTabStrip) findViewById(R.id.sliding_tabs);


		mTabs.add(new TabActivity.TabItem(R.string.download_tab_downloads,
				getString(R.string.download_tab_downloads), DownloadResourceGroupFragment.class));
		mTabs.add(new TabActivity.TabItem(R.string.download_tab_local,
				getString(R.string.download_tab_local), LocalIndexesFragment.class));
		mTabs.add(new TabActivity.TabItem(R.string.download_tab_updates,
				getString(R.string.download_tab_updates), UpdatesIndexFragment.class));

		viewPager.setAdapter(new TabActivity.OsmandFragmentPagerAdapter(getSupportFragmentManager(), mTabs));
		mSlidingTabLayout.setViewPager(viewPager);
		mSlidingTabLayout.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
			
			@Override
			public void onPageSelected(int position) {
				accessibilityAssistant.onPageSelected(position);
				visibleBanner.updateBannerInProgress();
			}
			
			@Override
			public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
				accessibilityAssistant.onPageScrolled(position, positionOffset, positionOffsetPixels);
			}
			
			@Override
			public void onPageScrollStateChanged(int state) {
				accessibilityAssistant.onPageScrollStateChanged(state);				
			}
		});
		viewPager.setCurrentItem(currentTab);

		visibleBanner = new BannerAndDownloadFreeVersion(findViewById(R.id.mainLayout), this, true);
		if (shouldShowFreeVersionBanner(getMyApplication())) {
			visibleBanner.updateFreeVersionBanner();
		}

		final Intent intent = getIntent();
		if (intent != null && intent.getExtras() != null) {
			filter = intent.getExtras().getString(FILTER_KEY);
			filterCat = intent.getExtras().getString(FILTER_CAT);
			filterGroup = intent.getExtras().getString(FILTER_GROUP);
		}
	}

	public boolean isInAppPurchaseAllowed() {
		return true;
	}

	@Override
	public void onInAppPurchaseError(InAppPurchaseTaskType taskType, String error) {
		visibleBanner.updateFreeVersionBanner();
		for (WeakReference<Fragment> ref : fragSet) {
			Fragment f = ref.get();
			if (f instanceof InAppPurchaseListener && f.isAdded()) {
				((InAppPurchaseListener) f).onError(taskType, error);
			}
		}
	}

	@Override
	public void onInAppPurchaseGetItems() {
		visibleBanner.updateFreeVersionBanner();
		for (WeakReference<Fragment> ref : fragSet) {
			Fragment f = ref.get();
			if (f instanceof InAppPurchaseListener && f.isAdded()) {
				((InAppPurchaseListener) f).onGetItems();
			}
		}
	}

	@Override
	public void onInAppPurchaseItemPurchased(String sku) {
		visibleBanner.updateFreeVersionBanner();
		for (WeakReference<Fragment> ref : fragSet) {
			Fragment f = ref.get();
			if (f instanceof InAppPurchaseListener && f.isAdded()) {
				((InAppPurchaseListener) f).onItemPurchased(sku);
			}
		}
	}

	@Override
	public void showInAppPurchaseProgress(InAppPurchaseTaskType taskType) {
		for (WeakReference<Fragment> ref : fragSet) {
			Fragment f = ref.get();
			if (f instanceof InAppPurchaseListener && f.isAdded()) {
				((InAppPurchaseListener) f).showProgress(taskType);
			}
		}
	}

	@Override
	public void dismissInAppPurchaseProgress(InAppPurchaseTaskType taskType) {
		for (WeakReference<Fragment> ref : fragSet) {
			Fragment f = ref.get();
			if (f instanceof InAppPurchaseListener && f.isAdded()) {
				((InAppPurchaseListener) f).dismissProgress(taskType);
			}
		}
	}

	public DownloadIndexesThread getDownloadThread() {
		return downloadThread;
	}

	public AccessibilityAssistant getAccessibilityAssistant() {
		return accessibilityAssistant;
	}

	@Override
	public void onAttachFragment(Fragment fragment) {
		fragSet.add(new WeakReference<Fragment>(fragment));
	}

	@Override
	protected void onResume() {
		super.onResume();
		initAppStatusVariables();
		getMyApplication().getAppCustomization().resumeActivity(DownloadActivity.class, this);
		downloadThread.setUiActivity(this);
		downloadInProgress();
	}


	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		switch (itemId) {
			case android.R.id.home:
				finish();
				return true;

		}
		return false;
	}

	public void setLocalIndexInfos(List<LocalIndexInfo> list) {
		this.localIndexInfos = list;
	}

	public List<LocalIndexInfo> getLocalIndexInfos() {
		return localIndexInfos;
	}

	@Override
	public void onPause() {
		super.onPause();
		getMyApplication().getAppCustomization().pauseActivity(DownloadActivity.class);
		downloadThread.resetUiActivity(this);
	}

	@Override
	@UiThread
	public void downloadHasFinished() {
		visibleBanner.updateBannerInProgress();
		if (downloadItem != null && downloadItem != getMyApplication().getRegions().getWorldRegion()
				&& !WorldRegion.WORLD_BASEMAP.equals(downloadItem.getRegionDownloadNameLC())) {

			if (!Algorithms.isEmpty(downloadTargetFileName)) {
				File f = new File(downloadTargetFileName);
				if (f.exists() && f.lastModified() > System.currentTimeMillis() - 10000) {
					getMyApplication().getDownloadThread().initSettingsFirstMap(downloadItem);
					showGoToMap(downloadItem);
				}
			}
			downloadItem = null;
			downloadTargetFileName = null;
		}
		if (!Algorithms.isEmpty(downloadTargetFileName)) {
			File f = new File(downloadTargetFileName);
			if (f.exists()) {
				String fileName = f.getName();
				if (fileName.endsWith(IndexConstants.FONT_INDEX_EXT)) {
					AlertDialog.Builder bld = new AlertDialog.Builder(this);
					bld.setMessage(R.string.restart_is_required);
					bld.setPositiveButton(R.string.shared_string_ok, new DialogInterface.OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int which) {
							android.os.Process.killProcess(android.os.Process.myPid());
						}
					});
					bld.setNegativeButton(R.string.shared_string_cancel, null);
					bld.show();
				} else if (fileName.startsWith(FileNameTranslationHelper.SEA_DEPTH)) {
					getMyApplication().getSettings().getCustomRenderBooleanProperty("depthContours").set(true);
				}
			}
			downloadItem = null;
			downloadTargetFileName = null;
		}
		for (WeakReference<Fragment> ref : fragSet) {
			Fragment f = ref.get();
			if (f instanceof DownloadEvents && f.isAdded()) {
				((DownloadEvents) f).downloadHasFinished();
			}
		}
	}

	@Override
	@UiThread
	public void downloadInProgress() {
		if (accessibilityAssistant.isUiUpdateDiscouraged())
			return;
		accessibilityAssistant.lockEvents();
		visibleBanner.updateBannerInProgress();
		showDownloadWorldMapIfNeeded();
		for (WeakReference<Fragment> ref : fragSet) {
			Fragment f = ref.get();
			if (f instanceof DownloadEvents && f.isAdded()) {
				((DownloadEvents) f).downloadInProgress();
			}
		}
		accessibilityAssistant.unlockEvents();
	}


	@Override
	@UiThread
	public void newDownloadIndexes() {
		visibleBanner.updateBannerInProgress();
		for (WeakReference<Fragment> ref : fragSet) {
			Fragment f = ref.get();
			if (f instanceof DownloadEvents && f.isAdded()) {
				((DownloadEvents) f).newDownloadIndexes();
			}
		}
		downloadHasFinished();
	}

	public void updateBanner() {
		visibleBanner.updateBannerInProgress();
	}

	private int getCurrentTab() {
		return viewPager.getCurrentItem();
	}

	public void showDialog(FragmentActivity activity, DialogFragment fragment) {
		fragment.show(activity.getSupportFragmentManager(), "dialog");
	}

	public static boolean isDownlodingPermitted(OsmandSettings settings) {
		final Integer mapsDownloaded = settings.NUMBER_OF_FREE_DOWNLOADS.get();
		int downloadsLeft = DownloadValidationManager.MAXIMUM_AVAILABLE_FREE_DOWNLOADS - mapsDownloaded;
		return Math.max(downloadsLeft, 0) > 0;
	}

	public static boolean shouldShowFreeVersionBanner(OsmandApplication application) {
		return (Version.isFreeVersion(application) && !InAppPurchaseHelper.isSubscribedToLiveUpdates(application)
				&& !InAppPurchaseHelper.isFullVersionPurchased(application))
				|| application.getSettings().SHOULD_SHOW_FREE_VERSION_BANNER.get();
	}
	
	
	public static class FreeVersionBanner {
		private final View freeVersionBanner;
		private final View freeVersionBannerTitle;
		private final TextView freeVersionDescriptionTextView;
		private final TextView downloadsLeftTextView;
		private final ProgressBar downloadsLeftProgressBar;
		
		private DownloadActivity ctx;

		private OnClickListener onCollapseListener = new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (freeVersionDescriptionTextView.getVisibility() == View.VISIBLE
						&& isDownlodingPermitted(ctx.getMyApplication().getSettings())) {
					collapseBanner();
				} else {
					ctx.getMyApplication().logEvent(ctx, "click_free_dialog");
					ChoosePlanDialogFragment.showFreeVersionInstance(ctx.getSupportFragmentManager());
				}
			}
		};
		
		public FreeVersionBanner(View view, final DownloadActivity ctx) {
			this.ctx = ctx;
			freeVersionBanner = view.findViewById(R.id.freeVersionBanner);
			downloadsLeftTextView = (TextView) freeVersionBanner.findViewById(R.id.downloadsLeftTextView);
			downloadsLeftProgressBar = (ProgressBar) freeVersionBanner.findViewById(R.id.downloadsLeftProgressBar);
			freeVersionDescriptionTextView = (TextView) freeVersionBanner
					.findViewById(R.id.freeVersionDescriptionTextView);
			freeVersionBannerTitle = freeVersionBanner.findViewById(R.id.freeVersionBannerTitle);
		}

		private void collapseBanner() {
			freeVersionDescriptionTextView.setVisibility(View.GONE);
			freeVersionBannerTitle.setVisibility(View.VISIBLE);
		}

		public void expandBanner() {
			freeVersionDescriptionTextView.setVisibility(View.VISIBLE);
			freeVersionBannerTitle.setVisibility(View.VISIBLE);
		}

		public void initFreeVersionBanner() {
			if (!shouldShowFreeVersionBanner(ctx.getMyApplication())) {
				freeVersionBanner.setVisibility(View.GONE);
				return;
			}
			freeVersionBanner.setVisibility(View.VISIBLE);
			downloadsLeftProgressBar.setMax(DownloadValidationManager.MAXIMUM_AVAILABLE_FREE_DOWNLOADS);
			freeVersionDescriptionTextView.setText(ctx.getString(R.string.free_version_message,
					DownloadValidationManager.MAXIMUM_AVAILABLE_FREE_DOWNLOADS));

			LinearLayout marksLinearLayout = (LinearLayout) freeVersionBanner.findViewById(R.id.marksLinearLayout);
			Space spaceView = new Space(ctx);
			LinearLayout.LayoutParams layoutParams =
					new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
			spaceView.setLayoutParams(layoutParams);
			marksLinearLayout.addView(spaceView);
			int markWidth = (int) (1 * ctx.getResources().getDisplayMetrics().density);
			int colorBlack = ctx.getResources().getColor(R.color.color_black);
			for (int i = 1; i < DownloadValidationManager.MAXIMUM_AVAILABLE_FREE_DOWNLOADS; i++) {
				View markView = new View(ctx);
				layoutParams = new LinearLayout.LayoutParams(markWidth, ViewGroup.LayoutParams.MATCH_PARENT);
				markView.setLayoutParams(layoutParams);
				markView.setBackgroundColor(colorBlack);
				marksLinearLayout.addView(markView);
				spaceView = new Space(ctx);
				layoutParams = new LinearLayout.LayoutParams(0,
						ViewGroup.LayoutParams.MATCH_PARENT, 1);
				spaceView.setLayoutParams(layoutParams);
				marksLinearLayout.addView(spaceView);
			}

			updateFreeVersionBanner();
			collapseBanner();
		}

		public void updateFreeVersionBanner() {
			if (!shouldShowFreeVersionBanner(ctx.getMyApplication())) {
				if (freeVersionBanner.getVisibility() == View.VISIBLE) {
					freeVersionBanner.setVisibility(View.GONE);
				}
				return;
			}
			setMinimizedFreeVersionBanner(false);
			OsmandSettings settings = ctx.getMyApplication().getSettings();
			final Integer mapsDownloaded = settings.NUMBER_OF_FREE_DOWNLOADS.get();
			downloadsLeftProgressBar.setProgress(mapsDownloaded);
			int downloadsLeft = DownloadValidationManager.MAXIMUM_AVAILABLE_FREE_DOWNLOADS - mapsDownloaded;
			downloadsLeft = Math.max(downloadsLeft, 0);
			downloadsLeftTextView.setText(ctx.getString(R.string.downloads_left_template, downloadsLeft));
			freeVersionBanner.findViewById(R.id.bannerTopLayout).setOnClickListener(onCollapseListener);
		}
		
		private void setMinimizedFreeVersionBanner(boolean minimize) {
			if (minimize && isDownlodingPermitted(ctx.getMyApplication().getSettings())) {
				collapseBanner();
				freeVersionBannerTitle.setVisibility(View.GONE);
			} else {
				freeVersionBannerTitle.setVisibility(View.VISIBLE);
			}
		}
		private void updateAvailableDownloads() {
			int activeTasks = ctx.getDownloadThread().getCountedDownloads();
			OsmandSettings settings = ctx.getMyApplication().getSettings();
			final Integer mapsDownloaded = settings.NUMBER_OF_FREE_DOWNLOADS.get() + activeTasks;
			downloadsLeftProgressBar.setProgress(mapsDownloaded);
		}
	}

	public static class BannerAndDownloadFreeVersion {
		
		private final View downloadProgressLayout;
		private final ProgressBar progressBar;
		private final TextView leftTextView;
		private final TextView rightTextView;

		private final DownloadActivity ctx;
		
		private boolean showSpace;
		private FreeVersionBanner freeVersionBanner;

		public BannerAndDownloadFreeVersion(View view, final DownloadActivity ctx, boolean showSpace) {
			this.ctx = ctx;
			this.showSpace = showSpace;
			freeVersionBanner = new FreeVersionBanner(view, ctx);
			
			downloadProgressLayout = view.findViewById(R.id.downloadProgressLayout);
			progressBar = (ProgressBar) view.findViewById(R.id.progressBar);
			leftTextView = (TextView) view.findViewById(R.id.leftTextView);
			rightTextView = (TextView) view.findViewById(R.id.rightTextView);

			freeVersionBanner.initFreeVersionBanner();
			updateBannerInProgress();
		}

		public void updateFreeVersionBanner() {
			freeVersionBanner.updateFreeVersionBanner();
		}

		public void updateBannerInProgress() {
			BasicProgressAsyncTask<?, ?, ?, ?> basicProgressAsyncTask = ctx.getDownloadThread().getCurrentRunningTask();
			final boolean isFinished = basicProgressAsyncTask == null
					|| basicProgressAsyncTask.getStatus() == AsyncTask.Status.FINISHED;
			if (isFinished) {
				downloadProgressLayout.setOnClickListener(null);
				updateDescriptionTextWithSize(ctx, downloadProgressLayout);
				if (ctx.getCurrentTab() == UPDATES_TAB_NUMBER || !showSpace) {
					downloadProgressLayout.setVisibility(View.GONE);
				} else {
					downloadProgressLayout.setVisibility(View.VISIBLE);
				}
				freeVersionBanner.updateFreeVersionBanner();
			} else {
				boolean indeterminate = basicProgressAsyncTask.isIndeterminate();
				String message = basicProgressAsyncTask.getDescription();
				int percent = basicProgressAsyncTask.getProgressPercentage();
				freeVersionBanner.setMinimizedFreeVersionBanner(true);
				freeVersionBanner.updateAvailableDownloads();
				downloadProgressLayout.setVisibility(View.VISIBLE);
				downloadProgressLayout.setOnClickListener(new OnClickListener() {
					@Override
					public void onClick(View v) {
						new ActiveDownloadsDialogFragment().show(ctx.getSupportFragmentManager(), "dialog");
					}
				});
				progressBar.setIndeterminate(indeterminate);
				if (indeterminate) {
					leftTextView.setText(message);
					rightTextView.setText(null);
				} else {
					progressBar.setProgress(percent);
					leftTextView.setText(message);
					rightTextView.setText(percent + "%");
				}
			}
		}
	}

	@SuppressLint("StaticFieldLeak")
	public void reloadLocalIndexes() {
		AsyncTask<Void, String, List<String>> task = new AsyncTask<Void, String, List<String>>() {
			@Override
			protected void onPreExecute() {
				super.onPreExecute();
				setSupportProgressBarIndeterminateVisibility(true);
			}

			@Override
			protected List<String> doInBackground(Void... params) {
				return getMyApplication().getResourceManager().reloadIndexes(IProgress.EMPTY_PROGRESS,
						new ArrayList<String>()
				);
			}

			@Override
			protected void onPostExecute(List<String> warnings) {
				setSupportProgressBarIndeterminateVisibility(false);
				if (!warnings.isEmpty()) {
					final StringBuilder b = new StringBuilder();
					boolean f = true;
					for (String w : warnings) {
						if (f) {
							f = false;
						} else {
							b.append('\n');
						}
						b.append(w);
					}
					Toast.makeText(DownloadActivity.this, b.toString(), Toast.LENGTH_LONG).show();
				}
				newDownloadIndexes();
			}
		};
		task.executeOnExecutor(singleThreadExecutor);
	}

	

	public void setDownloadItem(WorldRegion region, String targetFileName) {
		if (downloadItem == null) {
			downloadItem = region;
			downloadTargetFileName = targetFileName;
		} else if (region == null) {
			downloadItem = null;
			downloadTargetFileName = null;
		}
	}

	private void showGoToMap(WorldRegion region) {
		GoToMapFragment fragment = new GoToMapFragment();
		fragment.regionCenter = region.getRegionCenter();
		fragment.regionName = region.getLocaleName();
		fragment.show(getSupportFragmentManager(), GoToMapFragment.TAG);
	}

	private void showDownloadWorldMapIfNeeded() {
		if (getDownloadThread().getCurrentDownloadingItem() == null) {
			return;
		}
		IndexItem worldMap = getDownloadThread().getIndexes().getWorldBaseMapItem();
		if (!SUGGESTED_TO_DOWNLOAD_BASEMAP && worldMap != null && (!worldMap.isDownloaded() || worldMap.isOutdated()) &&
				!getDownloadThread().isDownloading(worldMap)) {
			SUGGESTED_TO_DOWNLOAD_BASEMAP = true;
			AskMapDownloadFragment fragment = new AskMapDownloadFragment();
			fragment.indexItem = worldMap;
			fragment.show(getSupportFragmentManager(), AskMapDownloadFragment.TAG);
		}
	}

	public static boolean hasPermissionToWriteExternalStorage(Context ctx) {
		return ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
				== PackageManager.PERMISSION_GRANTED;
	}

	public String getFilterAndClear() {
		String res = filter;
		filter = null;
		return res;
	}

	public String getFilterCatAndClear() {
		String res = filterCat;
		filterCat = null;
		return res;
	}

	public String getFilterGroupAndClear() {
		String res = filterGroup;
		filterGroup = null;
		return res;
	}

	@SuppressWarnings("deprecation")
	public static void updateDescriptionTextWithSize(DownloadActivity activity, View view) {
		TextView descriptionText = (TextView) view.findViewById(R.id.rightTextView);
		TextView messageTextView = (TextView) view.findViewById(R.id.leftTextView);
		ProgressBar sizeProgress = (ProgressBar) view.findViewById(R.id.progressBar);

		File dir = activity.getMyApplication().getAppPath("").getParentFile();
		String size = formatGb.format(new Object[]{0});
		int percent = 0;
		if (dir.canRead()) {
			StatFs fs = new StatFs(dir.getAbsolutePath());
			size = formatGb.format(new Object[]{(float) (fs.getAvailableBlocks()) * fs.getBlockSize() / (1 << 30)});
			percent = 100 - fs.getAvailableBlocks() * 100 / fs.getBlockCount();
		}
		sizeProgress.setIndeterminate(false);
		sizeProgress.setProgress(percent);
		String text = activity.getString(R.string.free, size);
		descriptionText.setText(text);
		descriptionText.setMovementMethod(LinkMovementMethod.getInstance());

		messageTextView.setText(R.string.device_memory);
	}

	public boolean isSrtmDisabled() {
		return srtmDisabled;
	}

	public boolean isSrtmNeedsInstallation() {
		return srtmNeedsInstallation;
	}

	public boolean isNauticalPluginDisabled() {
		return nauticalPluginDisabled;
	}

	public boolean isFreeVersion() {
		return freeVersion;
	}

	public void initAppStatusVariables() {
		srtmDisabled = OsmandPlugin.getEnabledPlugin(SRTMPlugin.class) == null;
		nauticalPluginDisabled = OsmandPlugin.getEnabledPlugin(NauticalMapsPlugin.class) == null;
		freeVersion = Version.isFreeVersion(getMyApplication());
		OsmandPlugin srtmPlugin = OsmandPlugin.getPlugin(SRTMPlugin.class);
		srtmNeedsInstallation = srtmPlugin == null || srtmPlugin.needsInstallation();

	}

	public static class AskMapDownloadFragment extends BottomSheetDialogFragment {
		public static final String TAG = "AskMapDownloadFragment";

		private static final String KEY_ASK_MAP_DOWNLOAD_ITEM_FILENAME = "key_ask_map_download_item_filename";
		private IndexItem indexItem;

		@Nullable
		@Override
		public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
			if (savedInstanceState != null) {
				String itemFileName = savedInstanceState.getString(KEY_ASK_MAP_DOWNLOAD_ITEM_FILENAME);
				if (itemFileName != null) {
					indexItem = getMyApplication().getDownloadThread().getIndexes().getIndexItem(itemFileName);
				}
			}
			View view = inflater.inflate(R.layout.ask_map_download_fragment, container, false);
			((ImageView) view.findViewById(R.id.titleIconImageView))
					.setImageDrawable(getIcon(R.drawable.ic_map, R.color.osmand_orange));

			Button actionButtonOk = (Button) view.findViewById(R.id.actionButtonOk);

			String titleText = null;
			String descriptionText = null;

			if (indexItem != null) {
				if (indexItem.getBasename().equalsIgnoreCase(WorldRegion.WORLD_BASEMAP)) {
					titleText = getString(R.string.index_item_world_basemap);
					descriptionText = getString(R.string.world_map_download_descr);
				}

				actionButtonOk.setText(getString(R.string.shared_string_download) + " (" + indexItem.getSizeDescription(getActivity()) + ")");
			}

			if (titleText != null) {
				((TextView) view.findViewById(R.id.titleTextView))
						.setText(titleText);
			}
			if (descriptionText != null) {
				((TextView) view.findViewById(R.id.descriptionTextView))
						.setText(descriptionText);
			}

			final ImageButton closeImageButton = (ImageButton) view.findViewById(R.id.closeImageButton);
			closeImageButton.setImageDrawable(getContentIcon(R.drawable.ic_action_remove_dark));
			closeImageButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					dismiss();
				}
			});

			actionButtonOk.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					if (indexItem != null) {
						((DownloadActivity) getActivity()).startDownload(indexItem);
						dismiss();
					}
				}
			});

			view.findViewById(R.id.actionButtonCancel)
					.setOnClickListener(new OnClickListener() {
						@Override
						public void onClick(View v) {
							dismiss();
						}
					});

			return view;
		}

		@Override
		public void onSaveInstanceState(@NonNull Bundle outState) {
			if (indexItem != null) {
				outState.putString(KEY_ASK_MAP_DOWNLOAD_ITEM_FILENAME, indexItem.getFileName());
			}
		}


	}

	public static class GoToMapFragment extends BottomSheetDialogFragment {
		public static final String TAG = "GoToMapFragment";

		private static final String KEY_GOTO_MAP_REGION_CENTER = "key_goto_map_region_center";
		private static final String KEY_GOTO_MAP_REGION_NAME = "key_goto_map_region_name";
		private LatLon regionCenter;
		private String regionName;

		@Override
		public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
			if (savedInstanceState != null) {
				regionName = savedInstanceState.getString(KEY_GOTO_MAP_REGION_NAME);
				regionName = regionName == null ? "" : regionName;
				Object rCenterObj = savedInstanceState.getSerializable(KEY_GOTO_MAP_REGION_CENTER);
				if (rCenterObj != null) {
					regionCenter = (LatLon) rCenterObj;
				} else {
					regionCenter = new LatLon(0, 0);
				}
			}

			View view = inflater.inflate(R.layout.go_to_map_fragment, container, false);
			((ImageView) view.findViewById(R.id.titleIconImageView))
					.setImageDrawable(getIcon(R.drawable.ic_map, R.color.osmand_orange));
			((TextView) view.findViewById(R.id.descriptionTextView))
					.setText(getActivity().getString(R.string.map_downloaded_descr, regionName));

			final ImageButton closeImageButton = (ImageButton) view.findViewById(R.id.closeImageButton);
			closeImageButton.setImageDrawable(getContentIcon(R.drawable.ic_action_remove_dark));
			closeImageButton.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					if (getActivity() instanceof DownloadActivity) {
						((DownloadActivity) getActivity()).setDownloadItem(null, null);
					}
					dismiss();
				}
			});

			view.findViewById(R.id.actionButton)
					.setOnClickListener(new OnClickListener() {
						@Override
						public void onClick(View v) {
							OsmandApplication app = (OsmandApplication) getActivity().getApplication();
							app.getSettings().setMapLocationToShow(
									regionCenter.getLatitude(),
									regionCenter.getLongitude(),
									5,
									new PointDescription(PointDescription.POINT_TYPE_WORLD_REGION_SHOW_ON_MAP, ""));

							dismiss();
							MapActivity.launchMapActivityMoveToTop(getActivity());
						}
					});

			return view;
		}

		@Override
		public void onSaveInstanceState(@NonNull Bundle outState) {
			outState.putString(KEY_GOTO_MAP_REGION_NAME, regionName);
			outState.putSerializable(KEY_GOTO_MAP_REGION_CENTER, regionCenter);
		}


	}

}
