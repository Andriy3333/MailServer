package ca.yorku.eecs3214.mail.mailbox;

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Writer interface that saves the content into a set of user mailboxes. Can be used in the same way as any other
 * regular Writer (e.g., FileWriter), as well as in combination with a BufferedWriter or PrintWriter.
 */
public class MailWriter extends Writer {

    public static final int BUFFER_SIZE = 4096;
    private final Collection<Writer> writers;
    private final StringBuffer buffer;

    /**
     * Creates a new MailWriter for a collection of mailbox recipients. Any content written to this MailWriter will be
     * copied to a new mail message in each of the mailboxes with exactly the same content.
     *
     * @param recipients Collection (list or set) of mailboxes where the content will be saved.
     */
    public MailWriter(Collection<Mailbox> recipients) {
        writers = recipients.stream().map(Mailbox::getNewMessageWriter).collect(Collectors.toList());
        buffer = new StringBuffer(BUFFER_SIZE);
    }

    /**
     * Writes the content to an internal buffer that will eventually be written to messages in all mailboxes. This is
     * the basis for all other <code>write()</code> methods, as they internally call this method with appropriate
     * values.
     *
     * @param cbuf Array of characters to be written
     * @param off  Offset from which to start writing characters
     * @param len  Number of characters to write
     */
    @Override
    public synchronized void write(char[] cbuf, int off, int len) throws IOException {
        if (buffer.length() + len > buffer.capacity())
            flush();
        buffer.append(cbuf, off, len);
    }

    /**
     * Flushes the content into the individual mailboxes.
     *
     * @throws IOException If there is an exception while saving content into any of the mailbox files.
     */
    @Override
    public synchronized void flush() throws IOException {
        if (buffer.length() == 0)
            return;
        for (Writer w : writers) {
            w.write(buffer.toString());
            w.flush();
        }
        buffer.setLength(0);
    }

    /**
     * Closes the MailWriter and corresponding mailbox item writers.
     *
     * @throws IOException If there is an exception while saving or closing any of the mailbox files.
     */
    @Override
    public void close() throws IOException {
        flush();
        for (Writer w : writers) {
            w.close();
        }
    }
}
