package com.faforever.client.map;

import com.faforever.client.config.CacheNames;
import com.faforever.client.config.ClientProperties;
import com.faforever.client.config.ClientProperties.Vault;
import com.faforever.client.fx.JavaFxUtil;
import com.faforever.client.game.FaStrings;
import com.faforever.client.i18n.I18n;
import com.faforever.client.map.FaMap.Type;
import com.faforever.client.map.generator.MapGeneratedEvent;
import com.faforever.client.map.generator.MapGeneratorService;
import com.faforever.client.preferences.PreferencesService;
import com.faforever.client.remote.FafService;
import com.faforever.client.task.CompletableTask;
import com.faforever.client.task.CompletableTask.Priority;
import com.faforever.client.task.TaskService;
import com.faforever.client.util.LuaUtil;
import com.faforever.client.vault.SearchConfig;
import com.github.nocatch.NoCatch;
import com.google.common.net.UrlEscapers;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static com.github.nocatch.NoCatch.noCatch;
import static java.nio.file.Files.list;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.util.stream.Collectors.toCollection;


@Lazy
@Service
public class MapService implements InitializingBean, DisposableBean {

  private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final PreferencesService preferencesService;
  private final TaskService taskService;
  private final ApplicationContext applicationContext;
  private final FafService fafService;
  private final I18n i18n;
  private final MapGeneratorService mapGeneratorService;
  private final EventBus eventBus;

  private final String mapDownloadUrlFormat;

  private Map<Path, FaMap> pathToMap;
  private Path customMapsDirectory;
  private ObservableList<FaMap> installedSkirmishMaps;
  private Map<String, FaMap> mapsByFolderName;
  private Thread directoryWatcherThread;


  public MapService(
    PreferencesService preferencesService,
    TaskService taskService,
    ApplicationContext applicationContext,
    FafService fafService,
    I18n i18n,
    ClientProperties clientProperties,
    MapGeneratorService mapGeneratorService,
    EventBus eventBus
  ) {
    this.preferencesService = preferencesService;
    this.taskService = taskService;
    this.applicationContext = applicationContext;
    this.fafService = fafService;
    this.i18n = i18n;
    this.mapGeneratorService = mapGeneratorService;
    this.eventBus = eventBus;

    Vault vault = clientProperties.getVault();
    this.mapDownloadUrlFormat = vault.getMapDownloadUrlFormat();

    pathToMap = new HashMap<>();
    installedSkirmishMaps = FXCollections.observableArrayList();
    mapsByFolderName = new HashMap<>();

    installedSkirmishMaps.addListener((ListChangeListener<FaMap>) change -> {
      while (change.next()) {
        for (FaMap faMap : change.getRemoved()) {
          mapsByFolderName.remove(faMap.getFolderName().toLowerCase());
        }
        for (FaMap faMap : change.getAddedSubList()) {
          mapsByFolderName.put(faMap.getFolderName().toLowerCase(), faMap);
        }
      }
    });
  }

  private static URL getDownloadUrl(String mapName, String baseUrl) {
    return noCatch(() -> new URL(String.format(baseUrl, UrlEscapers.urlFragmentEscaper().escape(mapName).toLowerCase(Locale.US))));
  }

  @Override
  public void afterPropertiesSet() {
    eventBus.register(this);
    customMapsDirectory = preferencesService.getPreferences().getForgedAlliance().getCustomMapsDirectory();
    JavaFxUtil.addListener(preferencesService.getPreferences().getForgedAlliance().pathProperty(), observable -> tryLoadMaps());
    JavaFxUtil.addListener(preferencesService.getPreferences().getForgedAlliance().customMapsDirectoryProperty(), observable -> tryLoadMaps());
    tryLoadMaps();
  }

  private void tryLoadMaps() {
    if (preferencesService.getPreferences().getForgedAlliance().getPath() == null
      || preferencesService.getPreferences().getForgedAlliance().getCustomMapsDirectory() == null) {
      return;
    }
    try {
      Files.createDirectories(customMapsDirectory);
      directoryWatcherThread = startDirectoryWatcher(customMapsDirectory);
    } catch (IOException e) {
      logger.warn("Could not start map directory watcher", e);
      // TODO notify user
    }
    loadInstalledMaps();
  }

  private Thread startDirectoryWatcher(Path mapsDirectory) {
    Thread thread = new Thread(() -> noCatch(() -> {
      WatchService watcher = mapsDirectory.getFileSystem().newWatchService();
      MapService.this.customMapsDirectory.register(watcher, ENTRY_DELETE);

      try {
        while (!Thread.interrupted()) {
          WatchKey key = watcher.take();
          key.pollEvents().stream()
            .filter(event -> event.kind() == ENTRY_DELETE)
            .forEach(event -> removeMap(mapsDirectory.resolve((Path) event.context())));
          key.reset();
        }
      } catch (InterruptedException e) {
        logger.debug("Watcher terminated ({})", e.getMessage());
      }
    }));
    thread.start();
    return thread;
  }

  private void loadInstalledMaps() {
    taskService.submitTask(new CompletableTask<Void>(Priority.LOW) {

      protected Void call() {
        updateTitle(i18n.get("mapVault.loadingMaps"));
        Path officialMapsPath = preferencesService.getPreferences().getForgedAlliance().getPath().resolve("maps");

        try (Stream<Path> customMapsDirectoryStream = list(customMapsDirectory)) {
          List<Path> mapPaths = new ArrayList<>();
          customMapsDirectoryStream.collect(toCollection(() -> mapPaths));
          Arrays.stream(OfficialMap.values())
            .map(map -> officialMapsPath.resolve(map.name()))
            .collect(toCollection(() -> mapPaths));

          long totalMaps = mapPaths.size();
          long mapsRead = 0;
          for (Path mapPath : mapPaths) {
            updateProgress(++mapsRead, totalMaps);
            addSkirmishMap(mapPath);
          }
        } catch (IOException e) {
          logger.warn("Maps could not be read from: " + customMapsDirectory, e);
        }
        return null;
      }
    });
  }

  private void removeMap(Path path) {
    installedSkirmishMaps.remove(pathToMap.remove(path));
  }

  private void addSkirmishMap(Path path) throws MapLoadException {
    try {
      FaMap faMap = readMap(path);
      pathToMap.put(path, faMap);
      if (!mapsByFolderName.containsKey(faMap.getFolderName()) && faMap.getType() == Type.SKIRMISH) {
        installedSkirmishMaps.add(faMap);
      }
    } catch (MapLoadException e) {
      logger.warn("Map could not be read: " + path.getFileName(), e);
    }
  }

  @Subscribe
  public void onMapGenerated(MapGeneratedEvent event) {
    addSkirmishMap(getPathForMap(event.getMapName()));
  }

  @NotNull
  public FaMap readMap(Path mapFolder) throws MapLoadException {
    if (!Files.isDirectory(mapFolder)) {
      throw new MapLoadException("Not a folder: " + mapFolder.toAbsolutePath());
    }

    try (Stream<Path> mapFolderFilesStream = list(mapFolder)) {
      Path scenarioLuaPath = mapFolderFilesStream
        .filter(file -> file.getFileName().toString().endsWith("_scenario.lua"))
        .findFirst()
        .orElseThrow(() -> new MapLoadException("Map folder does not contain a *_scenario.lua: " + mapFolder.toAbsolutePath()));

      LuaValue luaRoot = NoCatch.noCatch(() -> LuaUtil.loadFile(scenarioLuaPath), MapLoadException.class);
      LuaValue scenarioInfo = luaRoot.get("ScenarioInfo");
      LuaValue size = scenarioInfo.get("size");

      FaMap faMap = new FaMap();
      faMap.setFolderName(mapFolder.getFileName().toString());
      faMap.setDisplayName(scenarioInfo.get("name").toString());
      faMap.setDescription(FaStrings.removeLocalizationTag(scenarioInfo.get("description").toString()));
      faMap.setType(Type.fromString(scenarioInfo.get("type").toString()));
      faMap.setSize(MapSize.valueOf(size.get(1).toint(), size.get(2).toint()));
      faMap.setPlayers(scenarioInfo.get("Configurations").get("standard").get("teams").get(1).get("armies").length());

      LuaValue mapVersion = scenarioInfo.get("map_version");
      if (!mapVersion.isnil()) {
        faMap.setVersion(new ComparableVersion(mapVersion.toString()));
      }

      return faMap;
    } catch (IOException | LuaError e) {
      throw new MapLoadException(e);
    }
  }

  public ObservableList<FaMap> getInstalledMaps() {
    return installedSkirmishMaps;
  }

  public Optional<FaMap> getMapLocallyFromName(String mapFolderName) {
    logger.debug("Trying to find map '{}' locally", mapFolderName);
    ObservableList<FaMap> installedMaps = getInstalledMaps();
    synchronized (installedMaps) {
      for (FaMap faMap : installedMaps) {
        if (mapFolderName.equalsIgnoreCase(faMap.getFolderName())) {
          logger.debug("Found map {} locally", mapFolderName);
          return Optional.of(faMap);
        }
      }
    }
    return Optional.empty();
  }


  public boolean isOfficialMap(String mapName) {
    return OfficialMap.fromMapName(mapName) != null;
  }


  /**
   * Returns {@code true} if the given map is available locally, {@code false} otherwise.
   */

  public boolean isInstalled(String mapFolderName) {
    return mapsByFolderName.containsKey(mapFolderName.toLowerCase());
  }


  public CompletableFuture<Void> download(String technicalMapName) {
    URL mapUrl = getDownloadUrl(technicalMapName, mapDownloadUrlFormat);
    return downloadAndInstallMap(technicalMapName, mapUrl, null, null);
  }


  public CompletableFuture<Void> downloadAndInstallMap(FaMap map, @Nullable DoubleProperty progressProperty, @Nullable StringProperty titleProperty) {
    return downloadAndInstallMap(map.getFolderName(), map.getDownloadUrl(), progressProperty, titleProperty);
  }


  public CompletableFuture<List<FaMap>> getHighestRatedMaps(int count, int page) {
    return fafService.getHighestRatedMaps(count, page);
  }


  public CompletableFuture<List<FaMap>> getNewestMaps(int count, int page) {
    return fafService.getNewestMaps(count, page);
  }


  public CompletableFuture<List<FaMap>> getMostPlayedMaps(int count, int page) {
    return fafService.getMostPlayedMaps(count, page);
  }

  public CompletableFuture<Void> uninstallMap(FaMap map) {
    UninstallMapTask task = applicationContext.getBean(com.faforever.client.map.UninstallMapTask.class);
    task.setMap(map);
    return taskService.submitTask(task).getFuture();
  }

  public Path getPathForMap(FaMap map) {
    return getPathForMap(map.getFolderName());
  }

  public Path getPathForMap(String technicalName) {
    Path path = preferencesService.getPreferences().getForgedAlliance().getCustomMapsDirectory().resolve(technicalName);
    if (Files.notExists(path)) {
      return null;
    }
    return path;
  }

  public CompletableTask<Void> uploadMap(Path mapPath, boolean ranked) {
    MapUploadTask mapUploadTask = applicationContext.getBean(MapUploadTask.class);
    mapUploadTask.setMapPath(mapPath);
    mapUploadTask.setRanked(ranked);

    return taskService.submitTask(mapUploadTask);
  }

  @CacheEvict(CacheNames.MAPS)
  public void evictCache() {
    // Nothing to see here
  }

  /**
   * Tries to find a map my its folder name, first locally then on the server.
   */
  public CompletableFuture<Optional<FaMap>> findByMapFolderName(String folderName) {
    Path localMapFolder = getPathForMap(folderName);
    if (localMapFolder != null && Files.exists(localMapFolder)) {
      return CompletableFuture.completedFuture(Optional.of(readMap(localMapFolder)));
    }
    return fafService.findMapByFolderName(folderName);
  }

  public CompletableFuture<Boolean> hasPlayedMap(int playerId, String mapVersionId) {
    return fafService.getLastGameOnMap(playerId, mapVersionId)
      .thenApply(Optional::isPresent);
  }

  @Async
  public CompletableFuture<Integer> getFileSize(URL downloadUrl) {
    return CompletableFuture.completedFuture(noCatch(() -> downloadUrl
      .openConnection()
      .getContentLength()));
  }

  public CompletableFuture<List<FaMap>> findByQuery(SearchConfig searchConfig, int page, int count) {
    return fafService.findMapsByQuery(searchConfig, page, count);
  }

  public Optional<FaMap> findMap(String id) {
    return fafService.findMapById(id);
  }

  public CompletableFuture<List<FaMap>> getLadderMaps(int loadMoreCount, int page) {
    return fafService.getLadder1v1Maps(loadMoreCount, page);
  }

  private CompletableFuture<Void> downloadAndInstallMap(String folderName, URL downloadUrl, @Nullable DoubleProperty progressProperty, @Nullable StringProperty titleProperty) {
    if (mapGeneratorService.isGeneratedMap(folderName)) {
      return mapGeneratorService.generateMap(folderName).thenRun(() -> {
      });
    }

    DownloadMapTask task = applicationContext.getBean(DownloadMapTask.class);
    task.setMapUrl(downloadUrl);
    task.setFolderName(folderName);

    if (progressProperty != null) {
      progressProperty.bind(task.progressProperty());
    }
    if (titleProperty != null) {
      titleProperty.bind(task.titleProperty());
    }

    return taskService.submitTask(task).getFuture()
      .thenAccept(aVoid -> noCatch(() -> addSkirmishMap(getPathForMap(folderName))));
  }

  public CompletableFuture<List<FaMap>> getOwnedMaps(int playerId, int loadMoreCount, int page) {
    return fafService.getOwnedMaps(playerId, loadMoreCount, page);
  }

  public CompletableFuture<Void> hideMapVersion(FaMap map) {
    applicationContext.getBean(this.getClass()).evictCache();
    return fafService.hideMapVersion(map);
  }

  public CompletableFuture<Void> unrankMapVersion(FaMap map) {
    applicationContext.getBean(this.getClass()).evictCache();
    return fafService.unrankMapVersion(map);
  }

  @Override
  public void destroy() {
    Optional.ofNullable(directoryWatcherThread).ifPresent(Thread::interrupt);
  }

  public enum OfficialMap {
    SCMP_001, SCMP_002, SCMP_003, SCMP_004, SCMP_005, SCMP_006, SCMP_007, SCMP_008, SCMP_009, SCMP_010, SCMP_011,
    SCMP_012, SCMP_013, SCMP_014, SCMP_015, SCMP_016, SCMP_017, SCMP_018, SCMP_019, SCMP_020, SCMP_021, SCMP_022,
    SCMP_023, SCMP_024, SCMP_025, SCMP_026, SCMP_027, SCMP_028, SCMP_029, SCMP_030, SCMP_031, SCMP_032, SCMP_033,
    SCMP_034, SCMP_035, SCMP_036, SCMP_037, SCMP_038, SCMP_039, SCMP_040, X1MP_001, X1MP_002, X1MP_003, X1MP_004,
    X1MP_005, X1MP_006, X1MP_007, X1MP_008, X1MP_009, X1MP_010, X1MP_011, X1MP_012, X1MP_014, X1MP_017;

    private static final Map<String, OfficialMap> fromString;

    static {
      fromString = new HashMap<>();
      for (OfficialMap officialMap : values()) {
        fromString.put(officialMap.name(), officialMap);
      }
    }

    public static OfficialMap fromMapName(String mapName) {
      return fromString.get(mapName.toUpperCase());
    }
  }

  public enum PreviewSize {
    // These must match the preview URLs
    SMALL("small"), LARGE("large");

    String folderName;

    PreviewSize(String folderName) {
      this.folderName = folderName;
    }
  }
}