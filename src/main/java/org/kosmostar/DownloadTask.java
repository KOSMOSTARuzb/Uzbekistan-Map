package org.kosmostar;

import javafx.concurrent.Task;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DownloadTask extends Task<Void> {
    private final String fileUrl;
    private final File destFolder;

    public DownloadTask(String fileUrl, File destFolder) {
        this.fileUrl = fileUrl;
        this.destFolder = destFolder;
    }

    @Override
    protected Void call() throws Exception {
        if (!destFolder.exists() && !destFolder.mkdirs()) throw new Exception("Unable to create a new folder to download files into.");

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
                if (entry.isDirectory()) {
                    unzippedFile.mkdirs();
                } else {
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