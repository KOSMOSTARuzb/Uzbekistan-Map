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

    private final File dataDir = new File("map_data");
    private File mapFile;
    private File poiFile;
    private final File themeFile = new File(dataDir, "Elevate.xml"); // The main Elevate theme file

    private PauseTransition searchDebounce = new PauseTransition(Duration.millis(300));

    private MapView mapView;
    private Stage mainStage;
    private VBox poiPanel;
    private Accordion poiAccordion;
    private BorderPane mainLayout;

    private PoiPersistenceManager poiPersistenceManager;
    private PoiCategoryManager poiCategoryManager;

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
        AwtGraphicFactory.INSTANCE.getClass();

        StackPane root = new StackPane();
        mainLayout = new BorderPane();
        SwingNode swingNode = new SwingNode();
        mainLayout.setCenter(swingNode);

        // 1. Prepare Sidebar container
        poiPanel = new VBox(15);
        poiPanel.setPadding(new Insets(15));
        poiPanel.setPrefWidth(380);
        poiPanel.setStyle("-fx-background-color: #ffffff; -fx-border-color: #bdc3c7; -fx-border-width: 0 0 0 2; -fx-padding: 15;");
        makeResizable(poiPanel);

        // 2. Floating Action Button
        Button actionButton = new Button();
        actionButton.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold; " +
                "-fx-background-radius: 20; -fx-padding: 10 20; -fx-cursor: hand; " +
                "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 10, 0, 0, 0);");

        // BINDING: Hide floating button if sidebar is currently in the layout
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
        // The 'handle' is a thin 5-pixel wide area on the left edge
        region.setOnMouseMoved(e -> {
            // If the mouse is within the left 5 pixels, show the resize cursor
            if (e.getX() < 10) {
                region.setCursor(Cursor.H_RESIZE);
            } else {
                region.setCursor(Cursor.DEFAULT);
            }
        });

        region.setOnMouseDragged(e -> {
            if (region.getCursor() == Cursor.H_RESIZE) {
                // Calculate how much the user dragged
                // Since it's on the right side, dragging left (negative X)
                // increases the width.
                double newWidth = region.getWidth() - e.getX();

                double windowWidth = region.getScene().getWidth();

                if (newWidth > 200 && newWidth < (windowWidth - 50)) {
                    region.setPrefWidth(newWidth);
                }
            }
        });
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
        if (poiPersistenceManager == null || mainLayout.getRight() == null) return;

        Task<List<PointOfInterest>> queryTask = new Task<>() {
            @Override
            protected List<PointOfInterest> call() {
                // Search 500 meters around the click, no text pattern (null)
                Collection<PointOfInterest> pois = poiPersistenceManager.findNearPosition(
                        location, 500, null, null, location, 30, true
                );
                return new java.util.ArrayList<>(pois);
            }
        };

        queryTask.setOnSucceeded(e -> updateResultsList(queryTask.getValue()));
        new Thread(queryTask).start();
    }

    /**
     * Reusable method to fill the accordion with POI data
     */
    private void updateResultsList(List<PointOfInterest> results) {
        poiAccordion.getPanes().clear();

        if (results == null || results.isEmpty()) {
            TitledPane empty = new TitledPane("No results found", new Label("Try another search."));
            poiAccordion.getPanes().add(empty);
            return;
        }

        for (PointOfInterest poi : results) {
            // Just add the pane; the button inside will handle the movement
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
        VBox content = new VBox(12); // Slightly more spacing
        content.setPadding(new Insets(10));

        // 1. Category Label
        if (poi.getCategory() != null && poi.getCategory().getTitle() != null) {
            String catTitle = poi.getCategory().getTitle();
            Label catLabel = new Label("Category: " + (catTitle.contains("/") ? catTitle.split("/")[0] : catTitle));
            catLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50;");
            content.getChildren().add(catLabel);
        }

        // 2. Details Grid
        GridPane detailsGrid = new GridPane();
        detailsGrid.setHgap(10);
        detailsGrid.setVgap(6);
        ColumnConstraints col1 = new ColumnConstraints(100);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHgrow(Priority.ALWAYS);
        detailsGrid.getColumnConstraints().addAll(col1, col2);

        int row = 0;
        for (Tag tag : poi.getTags()) {
            Label key = new Label(tag.key.replace("_", " ") + ":");
            key.setStyle("-fx-font-weight: bold; -fx-text-fill: #7f8c8d; -fx-font-size: 11px;");
            Text val = new Text(tag.value);
            val.setWrappingWidth(220);

            detailsGrid.add(key, 0, row);
            detailsGrid.add(val, 1, row);
            row++;
        }

        content.getChildren().add(detailsGrid);

        // 3. THE "FLY TO" BUTTON
        Button flyButton = new Button("📍 Show on Map");
//        flyButton.setMaxWidth(Double.MAX_VALUE); // Fill width
        flyButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white; " +
                "-fx-font-weight: bold; -fx-padding: 8; -fx-background-radius: 5; -fx-cursor: hand;");

        // Hover effect
        flyButton.setOnMouseEntered(e -> flyButton.setStyle(flyButton.getStyle() + "-fx-background-color: #2980b9;"));
        flyButton.setOnMouseExited(e -> flyButton.setStyle(flyButton.getStyle() + "-fx-background-color: #3498db;"));

        flyButton.setOnAction(e -> {
            // Center the map on this specific POI
            mapView.getModel().mapViewPosition.setCenter(poi.getLatLong());
            // Optional: Zoom in close enough to see the street level
            mapView.getModel().mapViewPosition.setZoomLevel((byte) 16);
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

        // Spacer to push the close button to the far right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button panelCloseButton = new Button("✕"); // Using a Unicode 'X'
        panelCloseButton.setStyle("-fx-background-color: transparent; -fx-text-fill: #7f8c8d; -fx-font-size: 18px; -fx-font-weight: bold; -fx-cursor: hand;");
        panelCloseButton.setOnAction(e -> mainLayout.setRight(null)); // Closes the panel

        header.getChildren().addAll(titleLabel, spacer, panelCloseButton);
        return header;
    }

    private void handleOpenSidebar() {
        // Only build the UI if it's the very first time opening it
        if (poiPanel.getChildren().isEmpty()) {
            if (poiFile == null) {
                setupPoiDownloadButton();
            } else {
                setupPoiSearchUI();
            }
        }

        // Simply attach the existing, populated panel back to the layout
        mainLayout.setRight(poiPanel);
    }

    private void setupPoiSearchUI() {
        poiPanel.getChildren().clear();
        poiPanel.getChildren().add(createPanelHeader("Search Uzbekistan"));

        searchBox = new TextField();
        searchBox.setPromptText("Search (e.g. Bukhara, Hilton, Cafe)...");

        // Live Search Listener
        searchBox.textProperty().addListener((observable, oldValue, newValue) -> {
            searchDebounce.setOnFinished(e -> performGlobalSearch(newValue));
            searchDebounce.playFromStart();
        });

        poiAccordion = new Accordion();
        ScrollPane poiScrollPane = new ScrollPane(poiAccordion);
        poiScrollPane.setFitToWidth(true);
        poiScrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(poiScrollPane, Priority.ALWAYS);

        poiPanel.getChildren().addAll(searchBox, poiScrollPane);
    }

    private void performGlobalSearch(String query) {
        if (poiPersistenceManager == null || query == null || query.trim().length() < 2) {
            Platform.runLater(() -> poiAccordion.getPanes().clear());
            return;
        }

        Task<List<PointOfInterest>> searchTask = new Task<>() {
            @Override
            protected List<PointOfInterest> call() {
                // 1. Define the Bounding Box for Uzbekistan
                // (Min Lat, Min Lon, Max Lat, Max Lon)
                BoundingBox uzbekistanBounds = new BoundingBox(37.0, 55.0, 46.5, 74.0);

                // 2. Create a search pattern.
                // In Mapsforge POI files, 'name' is the standard tag key for the title of a place.
                List<Tag> patterns = new java.util.ArrayList<>();
                patterns.add(new Tag("name", query));

                // 3. Use findInRect to search the whole country
                Collection<PointOfInterest> results = poiPersistenceManager.findInRect(
                        uzbekistanBounds,
                        null,      // No category filter
                        patterns,  // This is our text search pattern
                        null,      // No specific sort order
                        50,        // Limit to 50 results
                        true       // Resolve categories
                );

                return new java.util.ArrayList<>(results);
            }
        };

        searchTask.setOnSucceeded(e -> updateResultsList(searchTask.getValue()));
        searchTask.setOnFailed(e -> searchTask.getException().printStackTrace());
        new Thread(searchTask).start();
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