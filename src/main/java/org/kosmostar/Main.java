package org.kosmostar;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.map.awt.graphics.AwtGraphicFactory;
import org.mapsforge.map.awt.util.AwtUtil;
import org.mapsforge.map.awt.view.MapView;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.ExternalRenderTheme;
import org.mapsforge.map.util.MapViewProjection;
import org.mapsforge.poi.storage.PoiPersistenceManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.*;

public class Main extends Application {

    private final String MAP_URL = "https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/mapsV5/asia/Uzbekistan.zip";
    private final String POI_URL = "https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/pois/mapsforge/asia/Uzbekistan.Poi.zip";
    private final String THEME_URL = "https://www.openandromaps.org/wp-content/users/tobias/Elevate.zip";

    private final File dataDir = new File("map_data");
    private File mapFile;
    private File poiFile;
    private final File themeFile = new File(dataDir, "Elevate.xml"); // The main Elevate theme file

    private MapView mapView;
    private Stage mainStage;
    private VBox poiPanel;
    private Label poiDetailsLabel;

    private PoiPersistenceManager poiPersistenceManager;
    private org.mapsforge.poi.storage.PoiCategoryManager poiCategoryManager;

    private TextField searchBox;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.mainStage = stage;
        stage.setTitle("Uzbekistan Interactive Map");

        // Check if data directory and files exist
        findLocalFiles();

        if (mapFile == null || !themeFile.exists()) {
            showDownloadUI();
        } else {
            showMapUI();
        }
    }

    /**
     * Searches the map_data folder for the extracted .map and .poi / .db files
     */
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
                            poiCategoryManager = poiPersistenceManager.getCategoryManager();
                        } catch (Exception ex) {
                            System.err.println("Could not open POI file: " + ex.getMessage());
                        }
                    }
                }
            }
        }
    }

    /**
     * Renders the Download UI on first launch
     */
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

        // 1. Download Map
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

    /**
     * Renders the Interactive Map UI
     */
    private void showMapUI() {
        AwtGraphicFactory.INSTANCE.getClass(); // Init AWT Graphic Factory
        StackPane root = new StackPane();
        SwingNode swingNode = new SwingNode();

        // --- NEW PANEL SETUP ---
        poiPanel = new VBox(10);
        poiPanel.setPadding(new Insets(15));
        poiPanel.setStyle("-fx-background-color: rgba(255, 255, 255, 0.9); -fx-background-radius: 8; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.4), 10, 0, 0, 0);");
        poiPanel.setMaxSize(250, 150);

        // Pin the panel to the Top Right
        StackPane.setAlignment(poiPanel, Pos.TOP_RIGHT);
        StackPane.setMargin(poiPanel, new Insets(20));

        // Initialize panel content based on POI availability
        if (poiFile == null) {
            setupPoiDownloadButton();
        } else {
            setupPoiSearchUI();
        }

        // Add the map first, then the panel so it floats on top
        root.getChildren().addAll(swingNode, poiPanel);

        SwingUtilities.invokeLater(() -> createMapContent(swingNode));

        Scene scene = new Scene(root, 1024, 768);
        mainStage.setScene(scene);
        mainStage.show();
    }

    /**
     * Sets up Mapsforge inside the Swing Node
     */
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

        // --- APPLIED EXTERNAL ELEVATE THEME HERE ---
        try {
            tileRendererLayer.setXmlRenderTheme(new ExternalRenderTheme(themeFile));
        } catch (FileNotFoundException e) {
            System.err.println("Theme file not found!");
            e.printStackTrace();
        }

        mapView.getLayerManager().getLayers().add(tileRendererLayer);

        // Center Map on Tashkent
        mapView.getModel().mapViewPosition.setCenter(new LatLong(41.2995, 69.2401));
        mapView.getModel().mapViewPosition.setZoomLevel((byte) 12);

        // --- POI INTERACTION LOGIC ---
        mapView.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // Convert screen click into GPS Coordinates
                LatLong tapLocation = mapView.getMapViewProjection().fromPixels(e.getX(), e.getY());

                // Switch back to JavaFX thread to show UI interactions
                Platform.runLater(() -> handleMapClick(tapLocation));
            }
        });

        for (MouseWheelListener listener : mapView.getMouseWheelListeners()) {
            mapView.removeMouseWheelListener(listener);
        }

        mapView.addMouseWheelListener(this::handleMouseWheelEvent);

        JPanel jPanel = new JPanel(new BorderLayout());
        jPanel.add(mapView, BorderLayout.CENTER);

        swingNode.setContent(jPanel);
    }

    private void handleMouseWheelEvent(MouseWheelEvent e){
        byte zoomDelta = (byte) (e.getWheelRotation() < 0 ? 1 : -1);
        byte currentZoom = mapView.getModel().mapViewPosition.getZoomLevel();
        final byte newZoom = (byte) (currentZoom + zoomDelta);

        // Prevent zooming past limits (adjust to your map's max zoom)
        if (newZoom < 0 || newZoom > 22) return;

        MapViewProjection proj = mapView.getMapViewProjection();
        LatLong mouseLatLong = proj.fromPixels(e.getX(), e.getY());
        if (mouseLatLong == null) return;

        // --- I DID THE MATH FOR YOU ---
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
        // ------------------------------

        // 1. Set the Pivot point so Mapsforge animates visually toward the mouse
        mapView.getModel().mapViewPosition.setPivot(mouseLatLong);

        // 2. Trigger the native Mapsforge zoom animation
        mapView.getModel().mapViewPosition.zoom(zoomDelta, true);

        // 3. Prevent the "snap-back" by locking in our calculated center
        // exactly when the Mapsforge animation finishes (250 milliseconds).
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

    /**
     * Handle the map click interactions (Query POI db here)
     */
    private void handleMapClick(LatLong location) {
        if (poiPersistenceManager == null) {
            if (poiDetailsLabel != null) poiDetailsLabel.setText("POI Database not initialized.");
            return;
        }

        // Search parameters
        int searchRadiusMeters = 500;
        int resultLimit = 50;

        Task<String> queryTask = new Task<>() {
            @Override
            protected String call() {
                StringBuilder results = new StringBuilder();
                String filterText = searchBox.getText().toLowerCase();

                java.util.Collection<org.mapsforge.poi.storage.PointOfInterest> pois =
                        poiPersistenceManager.findNearPosition(
                                location,           // point (center of search)
                                searchRadiusMeters, // distance (in meters)
                                null,               // filter (PoiCategoryFilter - null for all)
                                null,               // patterns (List<Tag> - null for all)
                                location,           // orderBy (Sort results relative to click location)
                                resultLimit,        // limit
                                true                // findCategories
                        );

                int count = 0;
                for (org.mapsforge.poi.storage.PointOfInterest poi : pois) {
                    String name = poi.getName() != null ? poi.getName() : "Unnamed Place";

                    // Filter based on user search text
                    if (!filterText.isEmpty() && !name.toLowerCase().contains(filterText)) {
                        continue;
                    }

                    results.append("📍 ").append(name).append("\n");
                    count++;
                    if (count >= 12) break; // Display limit for the panel
                }

                if (count == 0) {
                    return String.format("No POIs found within %dm.", searchRadiusMeters);
                } else {
                    return String.format("Near (within %dm):\n", searchRadiusMeters) + results.toString();
                }
            }
        };

        queryTask.setOnSucceeded(e -> poiDetailsLabel.setText(queryTask.getValue()));
        new Thread(queryTask).start();
    }

    private void setupPoiDownloadButton() {
        poiPanel.getChildren().clear();
        Button enablePoiBtn = new Button("Download Places Database");
        enablePoiBtn.setStyle("-fx-base: #3498db; -fx-text-fill: white; -fx-font-weight: bold;");

        enablePoiBtn.setOnAction(e -> {
            poiPanel.getChildren().clear();
            Label progressLabel = new Label("Downloading POI Database...");
            ProgressBar progressBar = new ProgressBar(0);
            progressBar.setPrefWidth(220);
            poiPanel.getChildren().addAll(progressLabel, progressBar);

            // Download asynchronously while user explores the map
            DownloadTask poiTask = new DownloadTask(POI_URL, dataDir);
            progressBar.progressProperty().bind(poiTask.progressProperty());
            progressLabel.textProperty().bind(poiTask.messageProperty());

            poiTask.setOnSucceeded(evt -> {
                findLocalFiles(); // Register the newly downloaded .poi file
                setupPoiSearchUI(); // Switch UI to Search mode
            });
            new Thread(poiTask).start();
        });

        poiPanel.getChildren().add(enablePoiBtn);
    }

    private void setupPoiSearchUI() {
        poiPanel.getChildren().clear();

        searchBox = new TextField();
        searchBox.setPromptText("Filter results (e.g. cafe)...");

        poiDetailsLabel = new Label("Click on the map to find nearby places.");
        poiDetailsLabel.setMinHeight(Region.USE_PREF_SIZE);
        VBox.setVgrow(poiDetailsLabel, Priority.ALWAYS);
        poiDetailsLabel.setWrapText(true);

        poiPanel.getChildren().addAll(new Label("Search & Info:"), searchBox, poiDetailsLabel);
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