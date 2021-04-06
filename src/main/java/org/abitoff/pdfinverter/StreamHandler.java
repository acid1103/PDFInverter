package org.abitoff.pdfinverter;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.abitoff.pdfinverter.util.IOFunctions;
import org.abitoff.pdfinverter.util.IOFunctions.IOFunction;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;

/**
 * {@code StreamHandler} manages the storage, byte-level modification, and IO of all streams in a PDF document which
 * require modification. A separate thread, started in
 * {@link org.apache.pdfbox.contentstream.PDFColorInverter#PDFColorInverter(PDPage, StreamHandler) PDFColorInverter},
 * manages two temporary files for each stream which must be managed. The first file is simply a copy of the raw stream
 * bytes. The second file is the working file which contains any modifications which {@code StreamHandler} has been
 * instructed to make.<br>
 * <br>
 * Instructions are sent to {@code StreamHandler} using a task queue system. {@link StreamHandlerTask tasks} are sent to
 * {@code StreamHandler} and are enqueued into its {@link StreamHandler#jobQueue job queue}. These jobs are then
 * completed by the {@code StreamHandler} thread.<br>
 * <br>
 * There's currently two types of tasks: {@link StreamAppendTask Append Tasks} and {@link StreamCopyTask Copy Tasks}.
 * Append Tasks append custom data to the referenced stream. Copy Tasks copy data from the original stream into the
 * modified stream.<br>
 * <br>
 * These tasks are created and queued by the {@link StreamHandler#writeToStream(PDContentStream, byte[]) writeToStream}
 * and {@link StreamHandler#copyFromStream(PDContentStream, long, long) copyFromStream} functions. These functions
 * return {@link CompletableFuture}s which complete/error once the task has been successfully or exceptionally
 * completed.<br>
 * <br>
 * Once all tasks have been completed, the {@code StreamHandler} is ready to be {@link StreamHandler#close() closed}.
 * (Alternatively, if {@code close} is called before {@code StreamHandler} has completed all its tasks, {@code close}
 * blocks until all tasks have been completed.) In closing, {@code StreamHandler} writes all the new stream data it has
 * accrued in its lifetime, closes all open resources, and deletes all temporary files it created - including the
 * temporary directory.
 * 
 * @author Steven Fontaine
 */
public class StreamHandler implements AutoCloseable, Runnable
{
	/** the {@code PDDocument} whose streams we are handling */
	private final PDDocument doc;
	/** temp directory for all our temp files */
	private final Path tempDir;
	/** map containing all our open byte channels */
	private final ConcurrentHashMap<Streamable, SeekableByteChannel[]> fileHandles = new ConcurrentHashMap<>();
	/** queue containing all our pending tasks */
	private final BlockingQueue<StreamHandlerTask> jobQueue = new LinkedBlockingDeque<>(100);
	/** used to signal that our queue is empty and we're no longer running */
	private final CompletableFuture<Void> runningFuture = new CompletableFuture<>();
	/** whether or not we should continue running when our job queue runs dry */
	private volatile boolean running = true;

	/**
	 * Construct a {@code StreamHandler} for the given {@link PDDocument}. Constructing a {@code StreamHandler} requires
	 * creating a temporary directory. If the application doesn't have sufficient permission or if any other error
	 * occurs during this process, an {@link IOException} will be thrown.
	 * 
	 * @throws IOException
	 *             if an error is encountered while creating the temporary directory
	 */
	public StreamHandler(PDDocument doc) throws IOException
	{
		this.doc = doc;
		// create temp dir for all future temp files
		this.tempDir = Files.createTempDirectory("");
	}

	@Override
	public void run()
	{
		// lifetime loop
		while (running || jobQueue.size() > 0)
		{
			if (!running && (jobQueue.size() % 10 == 0 || jobQueue.size() < 5))
				System.out.println("StreamHandler finishing remaining " + jobQueue.size() + " task(s).");

			// fetch a task from the queue, ignoring any interrupted exceptions
			final StreamHandlerTask task = IOFunctions.ignoreInterrupts(() -> jobQueue.poll(5, TimeUnit.SECONDS));
			// if we timeout, continue and try again
			if (task == null)
				continue;

			// grab the byte channels if they exist, or create and initialize them if they don't
			SeekableByteChannel[] handles;
			{
				final Streamable stream = task.stream;
				// holds an exception in the case of an IO error
				final Throwable[] t = new Throwable[1];
				handles = fileHandles.computeIfAbsent(stream, s ->
				{
					// if no byte channels, i.e. this is our first time seeing this stream, create them and fill the
					// copy file with the stream bytes
					try (InputStream is = stream.getInputStream(doc))
					{
						Path copy = Files.createTempFile(tempDir, "", "");
						Files.copy(is, copy, StandardCopyOption.REPLACE_EXISTING);
						SeekableByteChannel copyHandle = Files.newByteChannel(copy, StandardOpenOption.READ,
								StandardOpenOption.WRITE, StandardOpenOption.SYNC);

						Path overwrite = Files.createTempFile(tempDir, "", "");
						SeekableByteChannel overwriteHandle = Files.newByteChannel(overwrite, StandardOpenOption.READ,
								StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.SYNC);
						return new SeekableByteChannel[] {copyHandle, overwriteHandle};
					} catch (IOException e)
					{
						// log the exception
						t[0] = e;
						return null;
					}
				});

				// if no handles, we either err'd this time or a previous time
				if (handles == null)
				{
					Throwable e;
					if (t[0] != null)
						e = t[0];
					else
						e = new RuntimeException("Previously failed to create file handles for this stream. Refer to"
								+ " previous errors.");

					// exceptionally complete the task
					task.error(e);
					// move on to the next task
					continue;
				}
			}

			// try to run and complete the task. otherwise complete it exceptionally
			try
			{
				task.runTask(handles[0], handles[1]);
				task.complete();
			} catch (Exception e)
			{
				task.error(e);
			}
		}

		// complete the future, signaling that the lifetime loop is over
		runningFuture.complete(null);
	}

	@Override
	public void close()
	{
		// stop running once we're done executing tasks
		this.running = false;
		// block until we're done running
		try
		{
			boolean completed = false;
			while (!completed)
			{
				try
				{
					runningFuture.get();
				} catch (InterruptedException e)
				{
					// ignore interrupts
				}
				completed = true;
			}
		} catch (ExecutionException e)
		{
			// should never happen
		}

		// write all the new streams
		System.out.println("StreamHandler activity finished. Writing changes.");
		for (Entry<Streamable, SeekableByteChannel[]> entry: fileHandles.entrySet())
		{
			Streamable stream = entry.getKey();
			SeekableByteChannel in = entry.getValue()[0];
			SeekableByteChannel out = entry.getValue()[1];
			try
			{
				if (out != null)
				{
					out.position(0);
					try (InputStream is = Channels.newInputStream(out); OutputStream os = stream.getOutputStream(doc))
					{
						// copy our new stream into the output stream provided by the Streamable
						IOUtils.copy(is, os);
					}
				}
			} catch (IOException e)
			{
				System.err.println("Failed to write to stream.");
				e.printStackTrace();
			} finally
			{
				try
				{
					// tidy up our used resources!
					if (in != null)
						in.close();
					if (out != null)
						out.close();
				} catch (IOException e)
				{
					System.err.println("Failed to close file(s).");
					e.printStackTrace();
				}
			}
		}

		// delete any temp files/directories
		try
		{
			Files.walk(tempDir).forEach(f ->
			{
				try
				{
					if (!Files.isDirectory(f))
						Files.delete(f);
				} catch (IOException e)
				{
					System.err.println("Failed to delete file " + f.toAbsolutePath());
					e.printStackTrace();
				}
			});
			// we can only delete the dir if there's no files in it
			if (!Files.list(tempDir).findAny().isPresent())
				Files.delete(tempDir);
		} catch (IOException e)
		{
			System.err.println("Failed to delete tempDir.");
			e.printStackTrace();
		}
	}

	/**
	 * Copies the bytes from positions [start, end) (inclusive, exclusive) from the source stream to the new, modified
	 * stream
	 * 
	 * @param stream
	 *            the stream this copy is associated with
	 * @param start
	 *            start index
	 * @param end
	 *            end index
	 * @return a future which completes when the task is finished, or completes exceptionally if an error occurs while
	 *         trying to complete the task
	 * @throws InterruptedException
	 *             if we get interrupted while trying to queue the task
	 */
	public CompletableFuture<Void> copyFromStream(PDContentStream stream, long start, long end)
			throws InterruptedException
	{
		StreamHandlerTask task;
		if (stream instanceof PDPage)
			task = new StreamCopyTask(new Streamable((PDPage) stream), start, end);
		else
			task = new StreamCopyTask(new Streamable(getStream(stream)), start, end);
		return queueTask(task);
	}

	/**
	 * Copies the bytes in {@code data} to the new, modified stream
	 * 
	 * @param stream
	 *            the stream this write is associated with
	 * @param data
	 *            the new data to write to the stream
	 * @return a future which completes when the task is finished, or completes exceptionally if an error occurs while
	 *         trying to complete the task
	 * @throws InterruptedException
	 *             if we get interrupted while trying to queue the task
	 */
	public CompletableFuture<Void> writeToStream(PDContentStream stream, byte[] data) throws InterruptedException
	{
		StreamHandlerTask task;
		if (stream instanceof PDPage)
			task = new StreamAppendTask(new Streamable((PDPage) stream), data);
		else
			task = new StreamAppendTask(new Streamable(getStream(stream)), data);
		return queueTask(task);
	}

	/**
	 * Fetches the {@link PDStream} from the given {@link PDContentStream}.
	 */
	private PDStream getStream(PDContentStream stream)
	{
		if (stream instanceof PDFormXObject)
		{
			return ((PDFormXObject) stream).getStream();
		} else if (stream instanceof PDTilingPattern)
		{
			return ((PDTilingPattern) stream).getContentStream();
		} else if (stream instanceof PDType3CharProc)
		{
			return ((PDType3CharProc) stream).getContentStream();
		}
		return null;
	}

	/**
	 * Queues the given task and binds its callbacks to the future's completion methods. If this {@code StreamHandler}
	 * is no longer running, the future immediately completes exceptionally.
	 */
	private CompletableFuture<Void> queueTask(StreamHandlerTask task) throws InterruptedException
	{
		CompletableFuture<Void> future = new CompletableFuture<>();
		if (running)
		{
			task.onComplete = () -> future.complete(null);
			task.onError = (t) -> future.completeExceptionally(t);
			jobQueue.put(task);
		} else
		{
			future.completeExceptionally(
					new RuntimeException("StreamHandler is no longer running. Task will not be run."));
		}
		return future;
	}

	/**
	 * Base abstract class for a task accepted by a {@link StreamHandler}.
	 * 
	 * @author Steven Fontaine
	 */
	public static abstract class StreamHandlerTask
	{
		/** the {@code Streamable} which this task will operate on */
		private final Streamable stream;
		/** runs when this task completes successfully */
		private volatile Runnable onComplete;
		/** runs when this task completes exceptionally */
		private volatile Consumer<Throwable> onError;

		public StreamHandlerTask(Streamable stream)
		{
			this.stream = stream;
		}

		public abstract void runTask(SeekableByteChannel inFile, SeekableByteChannel outFile) throws Exception;

		private void complete()
		{
			if (onComplete != null)
				onComplete.run();
		}

		private void error(Throwable t)
		{
			if (onError != null)
				onError.accept(t);
		}
	}

	/**
	 * A task which appends new data to a stream
	 * 
	 * @author Steven Fontaine
	 */
	public static class StreamAppendTask extends StreamHandlerTask
	{
		private final byte[] data;

		/**
		 * @param stream
		 *            the {@code Streamable} whose stream this task relates to
		 * @param data
		 *            the data to append
		 */
		public StreamAppendTask(Streamable stream, byte[] data)
		{
			super(stream);
			this.data = data;
		}

		/**
		 * Appends the new data to {@code outFile}.
		 */
		public void runTask(SeekableByteChannel inFile, SeekableByteChannel outFile) throws IOException
		{
			ByteBuffer buf = ByteBuffer.wrap(data);
			buf.position(0);
			int written = 0;
			do
			{
				written += outFile.write(buf);
			} while (written < data.length);
		}
	}

	/**
	 * A task which copies data from the source stream to the modified version of the stream
	 * 
	 * @author Steven Fontaine
	 */
	public static class StreamCopyTask extends StreamHandlerTask
	{
		private final long start;
		private final long end;

		/**
		 * @param stream
		 *            the {@code Streamable} whose streams this task relates to
		 * @param start
		 *            the start position (inclusive) of the bytes to copy from the unmodified source stream
		 * @param end
		 *            the end position (exclusive) of the bytes
		 */
		public StreamCopyTask(Streamable stream, long start, long end)
		{
			super(stream);
			this.start = start;
			this.end = end;
		}

		/**
		 * Copies bytes from {@code inFile} to {@code outFile}
		 */
		public void runTask(SeekableByteChannel inFile, SeekableByteChannel outFile) throws IOException
		{
			inFile.position(start);
			ByteBuffer buf = ByteBuffer.allocate((int) (end - start));
			int readTot = 0;
			do
			{
				int read = inFile.read(buf);
				if (read == -1)
					throw new EOFException();
				readTot += read;
			} while (readTot < end - start);

			buf.position(0);
			int written = 0;
			do
			{
				written += outFile.write(buf);
			} while (written < buf.limit());
		}
	}

	/**
	 * A wrapper class which unifies the streaming functionality of several different classes in pdfbox. Specifically,
	 * it provides wrappers for getting input and output streams for stream-like objects in pdfbox, allowing reading and
	 * writing of the underlying data of these objects.
	 * 
	 * @author Steven Fontaine
	 */
	public static class Streamable
	{
		/**
		 * a new {@code Streamable} is created for every task. compObject is used to compare two different
		 * {@code Streamable}s which operate on the same streams.
		 */
		private final Object compObject;
		/** {@link InputStream} supplier */
		private final IOFunction<PDDocument, InputStream> isFunc;
		/** {@link OutputStream} supplier */
		private final IOFunction<PDDocument, OutputStream> osFunc;

		/**
		 * Constructs a {@code Streamable} from the given arguments.
		 * 
		 * @param compareObject
		 *            used to compare two different {@code Streamable}s which operate on the same underlying streams. if
		 *            two {@code Streamable}s operate on the same streams, it's not necessary that the
		 *            {@code Streamables} be equal to each other, but it is necessary that their {@code compareObject}s
		 *            be equal.
		 * @param inputStreamFunction
		 *            {@link InputStream} supplier. Each call to this function should provide a new, open
		 *            {@code InputStream}.
		 * @param outputStreamFunction
		 *            {@link OutputStream} supplier. Calling this function should provide an open, empty
		 *            {@code OutputStream}, which, when closed, writes its data to the underlying object, causing an
		 *            effect which will permanently modify the PDF upon saving.
		 */
		public Streamable(Object compareObject, IOFunction<PDDocument, InputStream> inputStreamFunction,
				IOFunction<PDDocument, OutputStream> outputStreamFunction)
		{
			this.compObject = compareObject;
			this.isFunc = inputStreamFunction;
			this.osFunc = outputStreamFunction;
		}

		private Streamable(PDStream stream)
		{
			this.compObject = stream;

			this.isFunc = (doc) -> stream.createInputStream();

			this.osFunc = (doc) -> stream.createOutputStream();
		}

		private Streamable(PDPage page)
		{
			this.compObject = page;

			this.isFunc = (doc) -> page.getContents();

			this.osFunc = (doc) -> new PDPageOutputStream(doc, page);
		}

		private InputStream getInputStream(PDDocument doc) throws IOException
		{
			return isFunc.apply(doc);
		}

		private OutputStream getOutputStream(PDDocument doc) throws IOException
		{
			return osFunc.apply(doc);
		}

		public int hashCode()
		{
			return compObject.hashCode();
		}

		public boolean equals(Object other)
		{
			return this == other
					|| other instanceof Streamable && ((Streamable) other).compObject.equals(this.compObject);
		}
	}

	/**
	 * {@link OutputStream} interface for a {@link PDPageContentStream}
	 * 
	 * @author Steven Fontaine
	 */
	private static final class PDPageOutputStream extends OutputStream
	{
		private final PDPageContentStream contentStream;

		public PDPageOutputStream(PDDocument doc, PDPage page) throws IOException
		{
			contentStream = new PDPageContentStream(doc, page, AppendMode.OVERWRITE, true, false);
		}

		@Override
		public void write(int b) throws IOException
		{
			this.write(new byte[] {(byte) b}, 0, 1);
		}

		@SuppressWarnings("deprecation")
		@Override
		public void write(byte b[], int off, int len) throws IOException
		{
			contentStream.appendRawCommands(Arrays.copyOfRange(b, off, off + len));
		}

		public void close() throws IOException
		{
			contentStream.close();
		}
	}
}
