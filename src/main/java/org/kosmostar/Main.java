package org.kosmostar;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import javafx.util.Duration;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tag;
import org.mapsforge.map.awt.graphics.AwtGraphicFactory;
import org.mapsforge.map.awt.util.AwtUtil;
import org.mapsforge.map.awt.view.MapView;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.ExternalRenderTheme;
import org.mapsforge.map.util.MapViewProjection;
import org.mapsforge.poi.storage.PoiCategoryManager;
import org.mapsforge.poi.storage.PoiPersistenceManager;
import org.mapsforge.poi.storage.PointOfInterest;
import org.mapsforge.poi.storage.UnknownPoiCategoryException;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.*;
import java.util.Collection;
import java.util.List;

public class Main extends Application {

    private final String MAP_URL = "https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/mapsV5/asia/Uzbekistan.zip";
    private final String POI_URL = "https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/pois/mapsforge/asia/Uzbekistan.Poi.zip";
    private final String THEME_URL = "https://www.openandromaps.org/wp-content/users/tobias/Elevate.zip";

    private final File dataDir = new File(System.getProperty("user.home"), ".uzbekistan_map_data");
    private File mapFile;
    private File poiFile;
    private final File themeFile = new File(dataDir, "Elevate.xml");

    private PauseTransition searchDebounce = new PauseTransition(Duration.millis(300));

    private MapView mapView;
    private Stage mainStage;
    private VBox poiPanel;
    private Accordion poiAccordion;
    private BorderPane mainLayout;

    private PoiPersistenceManager poiPersistenceManager;

    private TextField searchBox;
    private ToggleButton globalToggle;

    private enum SearchScope { GLOBAL, LOCAL, GEOZ }
    private SearchScope currentScope = SearchScope.GLOBAL;

    private final String[] GEO_CATEGORIES = {"city", "town", "village", "suburb", "hamlet", "administrative"};

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.mainStage = stage;
        stage.setTitle("Uzbekistan Interactive Map");

        findLocalFiles();

        if (mapFile == null || !themeFile.exists()) {
            showDownloadUI();
        } else {
            showMapUI();
        }
    }

    private void findLocalFiles() {
        if (!dataDir.exists()) return;

        File[] files = dataDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".map")) mapFile = file;
                if (file.getName().endsWith(".poi") || file.getName().endsWith(".db")) {
                    poiFile = file;
                    if (poiPersistenceManager == null) {
                        try {
                            poiPersistenceManager = org.mapsforge.poi.awt.storage.AwtPoiPersistenceManagerFactory
                                    .getPoiPersistenceManager(poiFile.getPath(), true);
                        } catch (Exception ex) {
                            System.err.println("Could not open POI file: " + ex.getMessage());
                        }
                    }
                }
            }
        }
    }

    private void showDownloadUI() {
        VBox layout = new VBox(15);
        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(30));

        Label infoLabel = new Label("First Launch: Downloading Map Data for Uzbekistan...");
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        Label progressLabel = new Label("0%");

        layout.getChildren().addAll(infoLabel, progressBar, progressLabel);
        Scene scene = new Scene(layout, 500, 250);
        mainStage.setScene(scene);
        mainStage.show();

        DownloadTask mapTask = new DownloadTask(MAP_URL, dataDir);
        progressBar.progressProperty().bind(mapTask.progressProperty());
        progressLabel.textProperty().bind(mapTask.messageProperty());

        mapTask.setOnSucceeded(e -> {
            infoLabel.setText("Downloading Elevate Map Theme...");
            DownloadTask themeTask = new DownloadTask(THEME_URL, dataDir);
            progressBar.progressProperty().unbind();
            progressLabel.textProperty().unbind();

            progressBar.progressProperty().bind(themeTask.progressProperty());
            progressLabel.textProperty().bind(themeTask.messageProperty());

            themeTask.setOnSucceeded(e3 -> {
                findLocalFiles();
                showMapUI();
            });
            new Thread(themeTask).start();
        });

        new Thread(mapTask).start();
    }

    private void showMapUI() {
        AwtGraphicFactory.INSTANCE.getClass();

        StackPane root = new StackPane();
        mainLayout = new BorderPane();
        SwingNode swingNode = new SwingNode();
        mainLayout.setCenter(swingNode);

        poiPanel = new VBox(15);
        poiPanel.setPadding(new Insets(15));
        poiPanel.setPrefWidth(380);
        poiPanel.setStyle("-fx-background-color: #ffffff; -fx-border-color: #bdc3c7; -fx-border-width: 0 0 0 2; -fx-padding: 15;");
        makeResizable(poiPanel);

        Button actionButton = new Button();
        actionButton.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold; " +
                "-fx-background-radius: 20; -fx-padding: 10 20; -fx-cursor: hand; " +
                "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 10, 0, 0, 0);");

        actionButton.visibleProperty().bind(mainLayout.rightProperty().isNull());

        actionButton.setText("Show Places Panel");
        actionButton.setStyle(actionButton.getStyle() + "-fx-background-color: #2c3e50;");
        actionButton.setOnAction(e -> handleOpenSidebar());

        AnchorPane buttonLayer = new AnchorPane(actionButton);
        AnchorPane.setTopAnchor(actionButton, 20.0);
        AnchorPane.setRightAnchor(actionButton, 20.0);
        buttonLayer.setPickOnBounds(false);

        root.getChildren().addAll(mainLayout, buttonLayer);

        SwingUtilities.invokeLater(() -> createMapContent(swingNode));

        Scene scene = new Scene(root, 1200, 800);
        mainStage.setScene(scene);
        mainStage.show();
    }

    private void makeResizable(Region region) {
        region.setOnMouseMoved(e -> {
            if (e.getX() < 10) {
                region.setCursor(Cursor.H_RESIZE);
            } else {
                region.setCursor(Cursor.DEFAULT);
            }
        });

        region.setOnMouseDragged(e -> {
            if (region.getCursor() == Cursor.H_RESIZE) {
                double newWidth = region.getWidth() - e.getX();

                double windowWidth = region.getScene().getWidth();

                if (newWidth > 200 && newWidth < (windowWidth - 50)) {
                    region.setPrefWidth(newWidth);
                }
            }
        });
    }

    private void createMapContent(SwingNode swingNode) {
        mapView = new MapView();
        mapView.getMapScaleBar().setVisible(true);
        mapView.getModel().displayModel.setFixedTileSize(512);

        TileCache tileCache = AwtUtil.createTileCache(
                mapView.getModel().displayModel.getTileSize(),
                mapView.getModel().frameBufferModel.getOverdrawFactor(),
                1024,
                new File(System.getProperty("java.io.tmpdir"), "mapsforge-cache")
        );

        MapDataStore mapDataStore = new MapFile(mapFile);
        TileRendererLayer tileRendererLayer = new TileRendererLayer(
                tileCache,
                mapDataStore,
                mapView.getModel().mapViewPosition,
                AwtGraphicFactory.INSTANCE
        );

        try {
            tileRendererLayer.setXmlRenderTheme(new ExternalRenderTheme(themeFile));
        } catch (FileNotFoundException e) {
            System.err.println("Theme file not found!");
            e.printStackTrace();
        }

        mapView.getLayerManager().getLayers().add(tileRendererLayer);

        mapView.getModel().mapViewPosition.setCenter(new LatLong(41.2995, 69.2401));
        mapView.getModel().mapViewPosition.setZoomLevel((byte) 12);

        mapView.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                LatLong tapLocation = mapView.getMapViewProjection().fromPixels(e.getX(), e.getY());

                Platform.runLater(() -> handleMapClick(tapLocation));
            }
        });

        for (MouseWheelListener listener : mapView.getMouseWheelListeners()) {
            mapView.removeMouseWheelListener(listener);
        }

        mapView.addMouseWheelListener(this::handleMouseWheelEvent);

        mapView.getModel().mapViewPosition.addObserver(() -> {
            if (globalToggle != null && !globalToggle.isSelected() && !searchBox.getText().isEmpty()) {
                Platform.runLater(() -> {
                    searchDebounce.playFromStart();
                });
            }
        });

        JPanel jPanel = new JPanel(new BorderLayout());
        jPanel.add(mapView, BorderLayout.CENTER);

        swingNode.setContent(jPanel);
    }

    private void handleMouseWheelEvent(MouseWheelEvent e){
        byte zoomDelta = (byte) (e.getWheelRotation() < 0 ? 1 : -1);
        byte currentZoom = mapView.getModel().mapViewPosition.getZoomLevel();
        final byte newZoom = (byte) (currentZoom + zoomDelta);

        if (newZoom < 0 || newZoom > 22) return;

        MapViewProjection proj = mapView.getMapViewProjection();
        LatLong mouseLatLong = proj.fromPixels(e.getX(), e.getY());
        if (mouseLatLong == null) return;

        int tileSize = mapView.getModel().displayModel.getTileSize();
        long newMapSize = org.mapsforge.core.util.MercatorProjection.getMapSize(newZoom, tileSize);

        double worldPixelX = org.mapsforge.core.util.MercatorProjection.longitudeToPixelX(mouseLatLong.longitude, newMapSize);
        double worldPixelY = org.mapsforge.core.util.MercatorProjection.latitudeToPixelY(mouseLatLong.latitude, newMapSize);

        double centerWorldPixelX = worldPixelX - e.getX() + (mapView.getWidth() / 2.0);
        double centerWorldPixelY = worldPixelY - e.getY() + (mapView.getHeight() / 2.0);

        final LatLong targetCenter = new LatLong(
                org.mapsforge.core.util.MercatorProjection.pixelYToLatitude(centerWorldPixelY, newMapSize),
                org.mapsforge.core.util.MercatorProjection.pixelXToLongitude(centerWorldPixelX, newMapSize)
        );

        mapView.getModel().mapViewPosition.setPivot(mouseLatLong);

        mapView.getModel().mapViewPosition.zoom(zoomDelta, true);

        Timer timer = new Timer(250, new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mapView.getModel().mapViewPosition.setMapPosition(
                        new org.mapsforge.core.model.MapPosition(targetCenter, newZoom)
                );
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void handleMapClick(LatLong location) {
        if (poiPersistenceManager == null || mainLayout.getRight() == null) return;

        Task<List<PointOfInterest>> queryTask = new Task<> () {
            @Override
            protected List<PointOfInterest> call() {
                org.mapsforge.poi.storage.PoiCategoryFilter filter = (currentScope == SearchScope.GEOZ) ? getGeoZFilter() : null;

                Collection<PointOfInterest> pois = poiPersistenceManager.findNearPosition(
                        location,
                        (currentScope == SearchScope.GEOZ) ? 20000 : 500,
                        filter,
                        null,
                        location,
                        30,
                        true
                );
                return new java.util.ArrayList<>(pois);
            }
        };

        queryTask.setOnSucceeded(e -> updateResultsList(queryTask.getValue()));
        new Thread(queryTask).start();
    }

    private void updateResultsList(List<PointOfInterest> results) {
        poiAccordion.getPanes().clear();

        if (results == null || results.isEmpty()) {
            TitledPane empty = new TitledPane("No results found", new Label("Try another search."));
            poiAccordion.getPanes().add(empty);
            return;
        }

        for (PointOfInterest poi : results) {
            poiAccordion.getPanes().add(createPoiPane(poi));
        }
    }

    private void setupPoiDownloadButton() {
        poiPanel.getChildren().clear();
        poiPanel.getChildren().add(createPanelHeader("Places Database"));
        Label info = new Label("Offline Places Database (POI)\n\nThis will allow you to search for cafes, hotels, and sights in Uzbekistan without an internet connection.");
        info.setWrapText(true);

        Button startDownloadBtn = new Button("Start Download (approx. 40MB)");
        startDownloadBtn.setMaxWidth(Double.MAX_VALUE);

        startDownloadBtn.setOnAction(e -> {
            poiPanel.getChildren().clear();
            Label progressLabel = new Label("Downloading POI Database...");
            ProgressBar progressBar = new ProgressBar(0);
            progressBar.setPrefWidth(350);
            poiPanel.getChildren().addAll(progressLabel, progressBar);

            DownloadTask poiTask = new DownloadTask(POI_URL, dataDir);
            progressBar.progressProperty().bind(poiTask.progressProperty());
            progressLabel.textProperty().bind(poiTask.messageProperty());

            poiTask.setOnSucceeded(evt -> {
                findLocalFiles();
                setupPoiSearchUI();
            });
            new Thread(poiTask).start();
        });

        poiPanel.getChildren().addAll(new Label("Places Database"), info, startDownloadBtn);
    }

    private TitledPane createPoiPane(PointOfInterest poi) {
        VBox content = new VBox(12);
        content.setPadding(new Insets(10));

        if (poi.getCategory() != null && poi.getCategory().getTitle() != null) {
            String catTitle = poi.getCategory().getTitle();
            Label catLabel = new Label("Category: " + (catTitle.contains("/") ? catTitle.split("/")[0] : catTitle));
            catLabel.setOnMouseClicked( e->{
                System.out.println("Name:"+catTitle+":");
            });
            catLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50;");
            content.getChildren().add(catLabel);
        }

        GridPane detailsGrid = new GridPane();
        detailsGrid.setHgap(10);
        detailsGrid.setVgap(6);
        ColumnConstraints col1 = new ColumnConstraints(100);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHgrow(Priority.ALWAYS);
        detailsGrid.getColumnConstraints().addAll(col1, col2);

        int row = 0;
        for (Tag tag : poi.getTags()) {
            String keyname = tag.key;
            if(keyname==null || keyname.isEmpty())keyname=" ";
            keyname = keyname.replace("_", " ").replace(':', ' ');
            keyname = keyname.substring(0, 1).toUpperCase() + keyname.substring(1);
            Label key = new Label( keyname + ":");
            key.setStyle("-fx-font-weight: bold; -fx-text-fill: #7f8c8d; -fx-font-size: 11px;");
            Text val = new Text(tag.value);
            val.setWrappingWidth(220);

            detailsGrid.add(key, 0, row);
            detailsGrid.add(val, 1, row);
            row++;
        }

        content.getChildren().add(detailsGrid);

        Button flyButton = new Button("📍 Show on Map");
        flyButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white; " +
                "-fx-font-weight: bold; -fx-padding: 8; -fx-background-radius: 5; -fx-cursor: hand;");

        flyButton.setOnMouseEntered(e -> flyButton.setStyle(flyButton.getStyle() + "-fx-background-color: #2980b9;"));
        flyButton.setOnMouseExited(e -> flyButton.setStyle(flyButton.getStyle() + "-fx-background-color: #3498db;"));

        flyButton.setOnAction(e -> {
            mapView.getModel().mapViewPosition.setCenter(poi.getLatLong());
            mapView.getModel().mapViewPosition.setZoomLevel((byte) (currentScope==SearchScope.GEOZ? 11 : 16));
        });

        content.getChildren().add(flyButton);

        String title = (poi.getName() != null) ? poi.getName() : "Unnamed Place";
        TitledPane pane = new TitledPane(title, content);
        return pane;
    }

    private HBox createPanelHeader(String title) {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button panelCloseButton = new Button("✕");
        panelCloseButton.setStyle("-fx-background-color: transparent; -fx-text-fill: #7f8c8d; -fx-font-size: 18px; -fx-font-weight: bold; -fx-cursor: hand;");
        panelCloseButton.setOnAction(e -> mainLayout.setRight(null));

        header.getChildren().addAll(titleLabel, spacer, panelCloseButton);
        return header;
    }

    private void handleOpenSidebar() {
        if (poiPanel.getChildren().isEmpty()) {
            if (poiFile == null) {
                setupPoiDownloadButton();
            } else {
                setupPoiSearchUI();
            }
        }

        mainLayout.setRight(poiPanel);
    }

    private void setupPoiSearchUI() {
        poiPanel.getChildren().clear();
        poiPanel.getChildren().add(createPanelHeader("Search Places"));

        searchBox = new TextField();
        searchBox.setPromptText("Search...");
        HBox.setHgrow(searchBox, Priority.ALWAYS);

        Button scopeBtn = new Button("Scope: Global");
        scopeBtn.setPrefWidth(110);
        scopeBtn.setStyle("-fx-cursor: hand; -fx-font-weight: bold;");

        scopeBtn.setOnAction(e -> {
            if (currentScope == SearchScope.GLOBAL) currentScope = SearchScope.LOCAL;
            else if (currentScope == SearchScope.LOCAL) currentScope = SearchScope.GEOZ;
            else currentScope = SearchScope.GLOBAL;

            scopeBtn.setText("Scope: " + (currentScope == SearchScope.GEOZ ? "Geo-Z" :
                    currentScope == SearchScope.LOCAL ? "Local" : "Global"));

            performSearch(searchBox.getText());
        });

        HBox searchRow = new HBox(5, searchBox, scopeBtn);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        searchBox.textProperty().addListener((obs, old, newValue) -> {
            searchDebounce.setOnFinished(e -> performSearch(newValue));
            searchDebounce.playFromStart();
        });

        poiAccordion = new Accordion();
        ScrollPane poiScrollPane = new ScrollPane(poiAccordion);
        poiScrollPane.setFitToWidth(true);
        poiScrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(poiScrollPane, Priority.ALWAYS);

        poiPanel.getChildren().addAll(searchRow, poiScrollPane);
    }

    private void performSearch(String query) {
        if (poiPersistenceManager == null || query == null || query.trim().length() < 2) {
            Platform.runLater(() -> poiAccordion.getPanes().clear());
            return;
        }

        final BoundingBox searchArea;
        if (currentScope == SearchScope.LOCAL) {
            MapViewProjection proj = mapView.getMapViewProjection();

            LatLong topLeft = proj.fromPixels(0, 0);
            LatLong bottomRight = proj.fromPixels(mapView.getWidth(), mapView.getHeight());

            if (topLeft != null && bottomRight != null) {
                searchArea = new BoundingBox(
                        bottomRight.latitude,
                        topLeft.longitude,
                        topLeft.latitude,
                        bottomRight.longitude
                );
            } else {
                searchArea = new BoundingBox(37.0, 55.0, 46.5, 74.0);
            }
        } else {
            searchArea = new BoundingBox(37.0, 55.0, 46.5, 74.0);
        }

        Task<List<PointOfInterest>> searchTask = new Task<>() {
            @Override
            protected List<PointOfInterest> call() {
                List<Tag> patterns = new java.util.ArrayList<>();
                patterns.add(new Tag("name", query));

                org.mapsforge.poi.storage.PoiCategoryFilter filter = (currentScope == SearchScope.GEOZ) ? getGeoZFilter() : null;


                Collection<PointOfInterest> results = poiPersistenceManager.findInRect(
                        searchArea,
                        filter,
                        patterns,
                        null,
                        50,
                        true
                );

                return new java.util.ArrayList<>(results);
            }
        };

        searchTask.setOnSucceeded(e -> updateResultsList(searchTask.getValue()));
        searchTask.setOnFailed(e -> searchTask.getException().printStackTrace());
        new Thread(searchTask).start();
    }

    private org.mapsforge.poi.storage.PoiCategoryFilter getGeoZFilter() {
        if (poiPersistenceManager == null) return null;

        org.mapsforge.poi.storage.WhitelistPoiCategoryFilter filter = new org.mapsforge.poi.storage.WhitelistPoiCategoryFilter();
        org.mapsforge.poi.storage.PoiCategoryManager manager = poiPersistenceManager.getCategoryManager();

        String[] geoKeys = {"City / Großstadt"};

        for (String key : geoKeys) {
            try {
                org.mapsforge.poi.storage.PoiCategory cat = manager.getPoiCategoryByTitle(key);
                if (cat != null) {
                    filter.addCategory(cat);
                }
            }catch (UnknownPoiCategoryException _){}
        }
        return filter;
    }

    @Override
    public void stop() throws Exception {
        try {
            if (poiPersistenceManager != null) {
                poiPersistenceManager.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (mapView != null) {
            mapView.destroyAll();
        }
        super.stop();
    }
}