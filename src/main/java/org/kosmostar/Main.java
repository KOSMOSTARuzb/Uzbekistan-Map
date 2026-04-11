package org.kosmostar;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Label;
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

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.mainStage = stage;
        stage.setTitle("Uzbekistan Interactive Map");

        // Check if data directory and files exist
        findLocalFiles();

        if (mapFile == null || poiFile == null || !themeFile.exists()) {
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
                if (file.getName().endsWith(".poi") || file.getName().endsWith(".db")) poiFile = file;
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
            // 2. Download POI
            infoLabel.setText("Downloading POI Database...");
            DownloadTask poiTask = new DownloadTask(POI_URL, dataDir);
            progressBar.progressProperty().bind(poiTask.progressProperty());
            progressLabel.textProperty().bind(poiTask.messageProperty());

            poiTask.setOnSucceeded(e2 -> {
                // 3. Download Elevate Theme
                infoLabel.setText("Downloading Elevate Map Theme...");
                DownloadTask themeTask = new DownloadTask(THEME_URL, dataDir);
                progressBar.progressProperty().bind(themeTask.progressProperty());
                progressLabel.textProperty().bind(themeTask.messageProperty());

                themeTask.setOnSucceeded(e3 -> {
                    findLocalFiles();
                    showMapUI();
                });
                new Thread(themeTask).start();
            });

            new Thread(poiTask).start();
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
        root.getChildren().add(swingNode);

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

        JPanel jPanel = new JPanel(new BorderLayout());
        jPanel.add(mapView, BorderLayout.CENTER);

        swingNode.setContent(jPanel);
    }

    /**
     * Handle the map click interactions (Query POI db here)
     */
    private void handleMapClick(LatLong location) {
        // Here you would run a spatial SQLite query on your poiFile (.db)
        // using the org.xerial.sqlitejdbc driver.
        System.out.println("Clicked on Map at: " + location.latitude + ", " + location.longitude);

        /* EXAMPLE OF CONNECTING TO THE POI DATABASE:
         * try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + poiFile.getAbsolutePath());
         *      Statement stmt = conn.createStatement()) {
         *      // Run standard SQL on OpenAndroMaps schema
         * } catch(Exception e) { e.printStackTrace(); }
         */

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Location Selected");
        alert.setHeaderText("Searching nearby POIs...");
        alert.setContentText(String.format("Latitude: %.5f\nLongitude: %.5f",
                location.latitude, location.longitude));
        alert.show();
    }

    @Override
    public void stop() {
        if (mapView != null) {
            mapView.destroyAll(); // Memory cleanup
        }
    }

    // =========================================================
    // INNER CLASS: Download Task Utility
    // =========================================================
    public static class DownloadTask extends Task<Void> {
        private final String fileUrl;
        private final File destFolder;

        public DownloadTask(String fileUrl, File destFolder) {
            this.fileUrl = fileUrl;
            this.destFolder = destFolder;
        }

        @Override
        protected Void call() throws Exception {
            if (!destFolder.exists()) destFolder.mkdirs();

            URL url = URI.create(fileUrl).toURL();
            URLConnection connection = url.openConnection();
            long fileSize = connection.getContentLengthLong();

            File tempZip = new File(destFolder, "temp_" + System.currentTimeMillis() + ".zip");
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(tempZip)) {

                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    updateProgress(downloaded, fileSize);
                    updateMessage(String.format("Downloading: %d MB / %d MB",
                            downloaded / 1024 / 1024, fileSize / 1024 / 1024));
                }
            }

            updateMessage("Extracting files...");
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(tempZip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File unzippedFile = new File(destFolder, entry.getName());

                    // --- FIXED UNZIP LOGIC TO SUPPORT THEME SUBFOLDERS ---
                    if (entry.isDirectory()) {
                        unzippedFile.mkdirs();
                    } else {
                        // Ensure the parent directory for the resource icon exists
                        unzippedFile.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(unzippedFile)) {
                            zis.transferTo(fos);
                        }
                    }
                }
            }

            tempZip.delete();
            updateMessage("Done!");
            return null;
        }
    }
}