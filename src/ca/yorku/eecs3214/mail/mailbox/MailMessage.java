package ca.yorku.eecs3214.mail.mailbox;

import java.io.File;

/**
 * An individual mail message.
 */
public class MailMessage {

    private final File file;
    private final long fileSize;
    private boolean deleted;

    /**
     * Creates a new mail message object whose content can be retrieved from a specified file.
     *
     * @param file The file object where the file content is found.
     */
    public MailMessage(File file) {
        this.file = file;
        this.fileSize = file.length();
        this.deleted = false;
    }

    /**
     * Returns the file object associated to the mail message.
     *
     * @return A File object containing the content of the mail message.
     */
    public File getFile() {
        return file;
    }

    /**
     * Returns the number of bytes in the mail message, including headers.
     *
     * @return The size of the mail message, in bytes.
     */
    public long getFileSize() {
        return fileSize;
    }

    /**
     * Returns true if the message is tagged to be deleted.
     *
     * @return true if the message is tagged to be deleted, and false otherwise.
     */
    public boolean isDeleted() {
        return deleted;
    }

    /**
     * Tags the message to be deleted. The file itself is not deleted yet, only marked for deletion. Marking the item
     * for deletion does not remove it from the corresponding mailbox or delete the corresponding file, as the message
     * may be undeleted with the <code>undelete()</code> method. To actually delete the file, use the
     * <code>deleteItemsTaggedForDeletion()</code> method in Mailbox.
     */
    public void tagForDeletion() {
        this.deleted = true;
    }

    /**
     * Resets the deletion tag so the message is no longer marked to be deleted.
     */
    public void undelete() {
        this.deleted = false;
    }
}
