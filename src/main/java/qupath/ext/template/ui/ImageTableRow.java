package qupath.ext.template.ui;
 
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
 
/**
 * A single row of data for the image viewer table.
 * <p>
 * This is a placeholder shape for whatever will eventually be pulled from the API -
 * one row per image, with an id, name, description, and image reference.
 * For now, the table is just populated with an empty list; rows can be added later
 * once the API call is wired up (e.g. via {@code tableView.getItems().addAll(...)}).
 */
public class ImageTableRow {
 
    private final StringProperty id = new SimpleStringProperty(this, "id", "");
    private final StringProperty name = new SimpleStringProperty(this, "name", "");
    private final StringProperty description = new SimpleStringProperty(this, "description", "");
    private final StringProperty image = new SimpleStringProperty(this, "image", "");
    private final StringProperty groupName = new SimpleStringProperty(this, "groupName", "");
    private final StringProperty groupId = new SimpleStringProperty(this, "groupId", "");
 
    public ImageTableRow() {}
 
    public ImageTableRow(String id, String name, String description, String groupName, String groupId, String image) {
        setId(id);
        setName(name);
        setDescription(description);
        setImage(image);
        setGroupName(groupName);
        setGroupId(groupId);
    }

    public String getGroupId() { return groupId.get(); }
    public void setGroupId(String value) { groupId.set(value); }
    public StringProperty groupIdProperty() { return groupId; }

    public String getGroupName() { return groupName.get(); }
    public void setGroupName(String value) { groupName.set(value); }
    public StringProperty groupNameProperty() { return groupName; }
 
    public String getId() { return id.get(); }
    public void setId(String value) { id.set(value); }
    public StringProperty idProperty() { return id; }
 
    public String getName() { return name.get(); }
    public void setName(String value) { name.set(value); }
    public StringProperty nameProperty() { return name; }
 
    public String getDescription() { return description.get(); }
    public void setDescription(String value) { description.set(value); }
    public StringProperty descriptionProperty() { return description; }
 
    public String getImage() { return image.get(); }
    public void setImage(String value) { image.set(value); }
    public StringProperty imageProperty() { return image; }
}
