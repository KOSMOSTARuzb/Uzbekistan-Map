module uzmap {
    requires javafx.controls;
    requires javafx.graphics;
    requires javafx.swing;

    requires java.desktop;
    requires java.sql;

    requires mapsforge.core;
    requires mapsforge.map;
    requires mapsforge.map.awt;
    requires mapsforge.poi;
    requires mapsforge.poi.awt;

    requires org.xerial.sqlitejdbc;
    requires mapsforge.map.reader;
    requires mapsforge.themes;

    opens org.kosmostar to javafx.graphics, javafx.controls;
    exports org.kosmostar;
}