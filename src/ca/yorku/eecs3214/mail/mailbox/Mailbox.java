package ca.yorku.eecs3214.mail.mailbox;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class Mailbox implements Iterable<MailMessage> {

    public static final String USER_FILE_NAME = "users.txt";
    public static final File USER_MAIL_BASE_DIRECTORY = new File("mail.store");
    public static final String MAIL_FILE_SUFFIX = ".mail";

    private static HashMap<String, String> userMap = null;

    private final String user;
    private final File mailDirectory;
    private List<MailMessage> messageList = null;

    /**
     * Initialized the mailbox for a specified user.
     *
     * @param user The user's address, including domain name.
     * @throws InvalidUserException If the user's address is not a valid address according to the list of accepted
     *                              addresses.
     */
    public Mailbox(String user) throws InvalidUserException {
        if (!isValidUser(user))
            throw new InvalidUserException();
        this.user = user;
        this.mailDirectory = new File(USER_MAIL_BASE_DIRECTORY, user);
    }

    /**
     * Initializes the map of user addresses and passwords from the users database. Only retrieves the data once, so
     * changes in the database require the server to be restarted.
     *
     * @return A map from a user's address to the user's password.
     */
    private static synchronized Map<String, String> getUserMap() {
        if (userMap != null)
            return userMap;
        userMap = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(USER_FILE_NAME))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] split = line.split(" ", 2);
                userMap.put(split[0], split[1]);
            }
        } catch (FileNotFoundException e) {
            // Do nothing, there are no users
        } catch (IOException e) {
            // Do nothing, accept no users
        }
        return userMap;
    }

    /**
     * Checks if a specified user address is a valid user, according to the user database.
     *
     * @param user The user's address, including domain name.
     * @return true if the user is in the database, and false otherwise.
     */
    public static boolean isValidUser(String user) {
        return getUserMap().containsKey(user);
    }

    public String getUsername() {
        return this.user;
    }

    /**
     * Checks the user's password and, if valid, loads the user's mailbox messages from the mail storage.
     *
     * @param password The user's password, unencrypted.
     * @throws MailboxNotAuthenticatedException If the password was not provided or is incorrect.
     */
    public void loadMessages(String password) throws MailboxNotAuthenticatedException {
        if (password == null || !password.equals(getUserMap().get(user)))
            throw new MailboxNotAuthenticatedException();
        this.messageList = Collections.emptyList();
        if (mailDirectory.exists() && mailDirectory.isDirectory()) {
            File[] files = mailDirectory.listFiles(f -> f.isFile() && f.getName().endsWith(MAIL_FILE_SUFFIX));
            if (files != null)
                this.messageList = Arrays.stream(files).sorted().map(MailMessage::new).collect(Collectors.toList());
        }
    }

    /**
     * Creates a new file to store a new incoming message, as well as a FileWriter associated to the file. Used by the
     * MailWriter class.
     *
     * @return A FileWriter object associated to the new file.
     */
    public FileWriter getNewMessageWriter() {
        // Creates the directory if it doesn't exist
        //noinspection ResultOfMethodCallIgnored
        mailDirectory.mkdirs();
        for (int i = 0; ; i++) {
            try {
                File file = new File(mailDirectory, i + MAIL_FILE_SUFFIX);
                if (!file.createNewFile())
                    continue;
                return new FileWriter(file);
            } catch (IOException e) {
                // continue, try next index
            }
        }
    }

    /**
     * Iterates over the mail messages load from the mailbox. May be used to create a for-each loop like:
     * <pre>
     *     for (MailMessage message : mailbox) { ... }
     * </pre>
     *
     * @return An Iterator object over the mail messages in the mailbox.
     * @throws MailboxNotAuthenticatedException If this operation is attempted before loading the list of messages.
     */
    @Override
    public Iterator<MailMessage> iterator() throws MailboxNotAuthenticatedException {
        if (messageList == null)
            throw new MailboxNotAuthenticatedException();
        return messageList.iterator();
    }

    /**
     * Returns the mail message at a particular index. The index is 1-based (i.e., the first message has the index 1
     * instead of 0), as per POP3 conventions. Note that this message may have been tagged for deletion, so it is
     * usually advisable to check isDeleted() before using the value of this entry.
     *
     * @param index The index of the message to be retrieved.
     * @return A MailMessage object corresponding to the mail message.
     * @throws MailboxNotAuthenticatedException If this operation is attempted before loading the list of messages.
     * @throws IndexOutOfBoundsException        If the index is less than 1 or larger than the number of messages.
     */
    public MailMessage getMailMessage(int index) throws MailboxNotAuthenticatedException, IndexOutOfBoundsException {
        if (messageList == null)
            throw new MailboxNotAuthenticatedException();
        return messageList.get(index - 1);
    }

    /**
     * Returns the number of mail messages in the mailbox.
     *
     * @param includeDeleted Should be set to true if deleted messages should be included, or false if they should be
     *                       ignored.
     * @return The number of mail messages in the mailbox.
     * @throws MailboxNotAuthenticatedException If this operation is attempted before loading the list of messages.
     */
    public int size(boolean includeDeleted) throws MailboxNotAuthenticatedException {
        if (messageList == null)
            throw new MailboxNotAuthenticatedException();
        if (includeDeleted)
            return messageList.size();
        return (int) messageList.stream().filter(item -> !item.isDeleted()).count();
    }

    /**
     * Returns the total size across all mail messages in the mailbox.
     *
     * @param includeDeleted Should be set to true if deleted messages should be included, or false if they should be
     *                       ignored.
     * @return The total size, in bytes, of all mail messages in the mailbox.
     * @throws MailboxNotAuthenticatedException If this operation is attempted before loading the list of messages.
     */
    public long getTotalUndeletedFileSize(boolean includeDeleted) throws MailboxNotAuthenticatedException {
        if (messageList == null)
            throw new MailboxNotAuthenticatedException();
        return messageList.stream().filter(item -> (includeDeleted || !item.isDeleted())).mapToLong(MailMessage::getFileSize).sum();
    }

    /**
     * Deletes the files for each message currently tagged for deletion. This operation cannot be undone, and for POP3
     * should only be performed when the corresponding session is complete. If the corresponding messages have not been
     * loaded, this method performs no operation.
     */
    public void deleteMessagesTaggedForDeletion() {
        if (messageList == null)
            return;
        for (MailMessage item : messageList) {
            if (item.isDeleted())
                //noinspection ResultOfMethodCallIgnored
                item.getFile().delete();
        }
    }

    /**
     * Exception used when attempting to obtain a mailbox for a user that is not in the database.
     */
    public static class InvalidUserException extends RuntimeException {
    }

    /**
     * Exception used when attempting to perform operations that require authentication, but the authentication was not
     * performed or was unsuccessful.
     */
    public static class MailboxNotAuthenticatedException extends RuntimeException {
    }
}
