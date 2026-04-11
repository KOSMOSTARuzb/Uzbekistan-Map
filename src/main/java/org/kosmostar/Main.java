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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.Stage;

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
import org.mapsforge.poi.storage.PoiPersistenceManager;
import org.mapsforge.poi.storage.PointOfInterest;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.*;
import java.util.List;

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
    private Accordion poiAccordion;
    private BorderPane mainLayout;

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
        AwtGraphicFactory.INSTANCE.getClass();

        StackPane root = new StackPane();
        mainLayout = new BorderPane();
        SwingNode swingNode = new SwingNode();
        mainLayout.setCenter(swingNode);

        // 1. Prepare Sidebar container
        poiPanel = new VBox(15);
        poiPanel.setPadding(new Insets(15));
        poiPanel.setPrefWidth(380);
        poiPanel.setStyle("-fx-background-color: #ffffff; -fx-border-color: #bdc3c7; -fx-border-width: 0 0 0 1;");

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
        if (poiPersistenceManager == null || mainLayout.getRight()==null) return;

        int searchRadiusMeters = 500;
        int resultLimit = 30;

        // Task now returns a List of POIs
        Task<List<org.mapsforge.poi.storage.PointOfInterest>> queryTask = new Task<>() {
            @Override
            protected List<org.mapsforge.poi.storage.PointOfInterest> call() {
                String filterText = searchBox.getText().toLowerCase();
                java.util.Collection<org.mapsforge.poi.storage.PointOfInterest> pois =
                        poiPersistenceManager.findNearPosition(
                                location, searchRadiusMeters, null, null, location, resultLimit, true
                        );

                // Filter them in the background thread
                return pois.stream()
                        .filter(p -> p.getName() != null && p.getName().toLowerCase().contains(filterText))
                        .collect(java.util.stream.Collectors.toList());
            }
        };

        queryTask.setOnSucceeded(e -> {
            List<PointOfInterest> results = queryTask.getValue();
            poiAccordion.getPanes().clear();

            if (results.isEmpty()) {
                TitledPane empty = new TitledPane("No results found", new Label("Try clicking elsewhere."));
                poiAccordion.getPanes().add(empty);
                return;
            }

            for (org.mapsforge.poi.storage.PointOfInterest poi : results) {
                poiAccordion.getPanes().add(createPoiPane(poi));
            }
        });

        new Thread(queryTask).start();
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

    private TitledPane createPoiPane(org.mapsforge.poi.storage.PointOfInterest poi) {
        VBox content = new VBox(8);
        content.setPadding(new Insets(10));

        // 1. Add Category Info
        if (poi.getCategory() != null) {
            Label catLabel = new Label("Category: " + poi.getCategory().getTitle());
            catLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50;");
            content.getChildren().add(catLabel);
        }

        // 2. Add all Tags (The "hidden" data)
        GridPane detailsGrid = new GridPane();
        detailsGrid.setHgap(10);
        detailsGrid.setVgap(6);

        // --- FORCE COLUMN SIZING ---
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setMinWidth(80); // Fixed width for keys (e.g. "Cuisine:")

        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHgrow(Priority.ALWAYS); // Value column takes the rest of the space

        detailsGrid.getColumnConstraints().addAll(col1, col2);

        int row = 0;
        for (org.mapsforge.core.model.Tag tag : poi.getTags()) {
            Label key = new Label(tag.key.replace("_", " ") + ":"); // Clean up underscores
            key.setStyle("-fx-font-weight: bold; -fx-text-fill: #7f8c8d; -fx-font-size: 11px;");

            // Use a Text node for the value to allow multi-line wrapping
            Text val = new Text(tag.value);
            val.setWrappingWidth(240); // Matches the remaining space in the 380px panel

            detailsGrid.add(key, 0, row);
            detailsGrid.add(val, 1, row);
            row++;
        }

        content.getChildren().add(detailsGrid);

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
        if (poiFile == null) {
            setupPoiDownloadButton();
        } else {
            setupPoiSearchUI();
        }
        mainLayout.setRight(poiPanel);
    }

    private void setupPoiSearchUI() {
        poiPanel.getChildren().clear();
        poiPanel.getChildren().add(createPanelHeader("Nearby Places"));

        searchBox = new TextField();
        searchBox.setPromptText("Filter results (e.g. cafe)...");

        poiAccordion = new Accordion();

        ScrollPane poiScrollPane = new ScrollPane(poiAccordion);
        poiScrollPane.setFitToWidth(true);
        poiScrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(poiScrollPane, Priority.ALWAYS);

        poiPanel.getChildren().addAll(searchBox, poiScrollPane);
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