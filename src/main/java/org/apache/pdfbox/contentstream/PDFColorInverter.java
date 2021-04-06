// Copyright (c) 2020 Steven Fontaine
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package org.apache.pdfbox.contentstream;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.abitoff.pdfinverter.StreamHandler;
import org.abitoff.pdfinverter.util.IOFunctions;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorProcessor;
import org.apache.pdfbox.contentstream.operator.color.SetColor;
import org.apache.pdfbox.contentstream.operator.color.SetColorOperatorUtils;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingColor;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdfparser.PDFStreamColorSlicer;
import org.apache.pdfbox.pdfparser.PDFStreamColorSlicer.StreamSlice;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * <p>
 * An extension of the {@link PDFGraphicsStreamEngine} which hijacks the graphics stream in order to find the locations
 * of all {@link SetColor} operators and replace them with inverted colors.
 * </p>
 * <p>
 * This class must be in the package {@code org.apache.pdfbox.contentstream} in order to inherit many fields and methods
 * from {@link PDFGraphicsStreamEngine}.
 * </p>
 * 
 * @see PDFColorInverter#invert(PDDocument, float[], boolean)
 * 
 * @author Steven Fontaine
 *
 */
public class PDFColorInverter extends PDFGraphicsStreamEngine implements AutoCloseable
{
	/** A map containing our current writing position in any stream */
	private final Map<PDContentStream, Long> positionMap = new HashMap<>();

	/** A set of all encountered streams */
	private final Set<PDContentStream> contentStreamSet = new HashSet<>();

	/** Our instance of a {@link StreamHandler} */
	private final StreamHandler streamHandler;

	private final float[] background;

	protected PDFColorInverter(PDPage page, StreamHandler streamHandler, float[] background)
	{
		super(page);

		this.background = background;

		// start the stream handler
		this.streamHandler = streamHandler;
		new Thread(streamHandler).start();
	}

	/**
	 * Very similar to {@link PDFGraphicsStreamEngine#processStreamOperators(PDContentStream) processStreamOperators}.
	 * Additionally, it finds the bounds of each {@link SetColor} operator, constructs a
	 * {@link SetColorOperatorStreamSlice}, inverts the colors, and instructs {@link StreamHandler} to write the
	 * modifications.
	 */
	protected void processStreamOperators(PDContentStream contentStream) throws IOException
	{
		// have we seen this stream before?
		boolean processStream = !contentStreamSet.contains(contentStream);
		contentStreamSet.add(contentStream);

		// add the background rect
		if (processStream && contentStream instanceof PDPage)
			writeBackground((PDPage) contentStream, background);

		List<StreamSlice> arguments = new ArrayList<StreamSlice>();
		PDFStreamColorSlicer parser = new PDFStreamColorSlicer(contentStream);

		// keep track of the previous valid slice to use when we hit the end of the stream
		StreamSlice lastSlice = null;
		StreamSlice slice = parser.getNextSlice();
		while (slice.token != null)
		{
			if (slice.token instanceof Operator)
			{
				OperatorProcessor processor =
						processOperatorAndReturnProcessor((Operator) slice.token, mapToCOSBaseList(arguments));
				if (processStream && processor instanceof SetColor)
				{
					// get relevant info about the SetColor slice
					SetColorOperatorStreamSlice scoss = generateColorSlice(arguments, slice, (SetColor) processor);
					// invert the color and write it back to the stream
					handleSlice(contentStream, scoss);
				}

				arguments.clear();
			} else
			{
				arguments.add(slice);
			}

			lastSlice = slice;
			slice = parser.getNextSlice();
		}

		if (processStream)
			handleEndOfStream(contentStream, lastSlice);
	}

	private void writeBackground(PDPage page, float[] background)
	{
		float width = page.getMediaBox().getWidth();
		float height = page.getMediaBox().getHeight();

		String nsc = background[0] + " " + background[1] + " " + background[2] + " rg ";
		String rect = "0 0 " + width + " " + height + " re ";
		String fill = "f ";

		String command = nsc + rect + fill;
		byte[] commandBytes = command.getBytes(StandardCharsets.UTF_8);

		IOFunctions.ignoreInterrupts(() -> streamHandler.writeToStream(page, commandBytes));
	}

	/**
	 * Write the data at the end of streams
	 * 
	 * @param contentStream
	 *            the stream being sliced
	 * @param lastSlice
	 *            the last valid slice, containing the end bound of the stream
	 */
	private void handleEndOfStream(PDContentStream contentStream, StreamSlice lastSlice)
	{
		if (lastSlice != null)
		{
			// get our current position and copy everything between our last position and the very end of the stream
			long pos = positionMap.computeIfAbsent(contentStream, s -> 0l);
			IOFunctions.ignoreInterrupts(() -> streamHandler.copyFromStream(contentStream, pos, lastSlice.end + 1));
		}
	}

	/**
	 * Instruct the {@link StreamHandler} to copy all the data between the last slice and this one. Then invert the
	 * colors in the given slice and instruct the {@link StreamHandler} to write the new data as well.
	 * 
	 * @param contentStream
	 *            the stream being sliced
	 * @param slice
	 *            the slice to handle
	 * @throws IOException
	 *             if something goes wrong
	 */
	private void handleSlice(PDContentStream contentStream, SetColorOperatorStreamSlice slice) throws IOException
	{
		// get our current position in this stream
		long pos = positionMap.computeIfAbsent(contentStream, s -> 0l);
		// copy all the data between the last color slice and this one
		IOFunctions.ignoreInterrupts(() -> streamHandler.copyFromStream(contentStream, pos, slice.start));

		if (slice.color.getColorSpace() instanceof PDPattern)
		{
			System.err.println("Patterns aren't currently supported!");
			// patterns aren't currently supported, so just copy the old operation
			IOFunctions.ignoreInterrupts(() -> streamHandler.copyFromStream(contentStream, slice.start, slice.end));
		} else
		{
			PDColorSpace cs = slice.color.getColorSpace();
			// float representation of rgb gives ~24 bits per component
			float[] rgb = cs.toRGB(slice.color.getComponents());
			// invert the rgb values
			rgb[0] = 1f - rgb[0];
			rgb[1] = 1f - rgb[1];
			rgb[2] = 1f - rgb[2];

			// construct the new operation
			String command = " " + rgb[0] + " " + rgb[1] + " " + rgb[2] + " " + (slice.stroking ? "RG " : "rg ");
			byte[] commandBytes = command.getBytes(StandardCharsets.UTF_8);
			// instruct the stream handler to write the new command
			IOFunctions.ignoreInterrupts(() -> streamHandler.writeToStream(contentStream, commandBytes));
		}

		// update our position for this stream
		positionMap.put(contentStream, slice.end);
	}

	/**
	 * Constructs a {@link SetColorOperatorStreamSlice} from the given arguments, {@link StreamSlice}, and processor
	 */
	private SetColorOperatorStreamSlice generateColorSlice(List<StreamSlice> arguments, StreamSlice slice,
			SetColor processor)
	{
		boolean stroking = processor instanceof SetStrokingColor;
		int availableArguments = arguments.size();
		int numArgs = getCurrentNumOfColorComponents(stroking, availableArguments);

		// the arguments stack might contain irrelevant slices underneath the relevant slices, so we need to index it
		// from the top (higher index) of the stack.
		StreamSlice firstArg = arguments.get(arguments.size() - numArgs);
		int start = firstArg.start;
		int end = slice.end;
		PDColor color = SetColorOperatorUtils.getColor(processor);
		SetColorOperatorStreamSlice colorSlice = new SetColorOperatorStreamSlice(start, end, color, stroking);
		return colorSlice;
	}

	/**
	 * Maps a list of {@link StreamSlice}s to a list of {@link COSBase}s in the same way as
	 * {@link PDFGraphicsStreamEngine#processStreamOperators(PDContentStream)}.
	 */
	private static List<COSBase> mapToCOSBaseList(List<StreamSlice> slices)
	{
		return slices.stream().map(s ->
		{
			if (s.token instanceof COSObject)
				return ((COSObject) s.token).getObject();
			else
				return (COSBase) s.token;
		}).collect(Collectors.toList());
	}

	/**
	 * Gets the number of color components from the current {@link PDFGraphicsStreamEngine#getGraphicsState() graphics
	 * state}.
	 */
	private int getCurrentNumOfColorComponents(boolean stroking, int availableArguments)
	{
		if (stroking)
		{
			PDColorSpace cs = getGraphicsState().getStrokingColorSpace();
			if (cs instanceof PDPattern)
				return availableArguments; // this is basically what SetColor#process() does
			else
				return cs.getNumberOfComponents();
		} else
		{
			PDColorSpace cs = getGraphicsState().getNonStrokingColorSpace();
			if (cs instanceof PDPattern)
				return availableArguments; // this is basically what SetColor#process() does
			else
				return cs.getNumberOfComponents();
		}
	}

	/**
	 * Copy of {@link PDFGraphicsStreamEngine#processOperator(Operator, List)} which returns the
	 * {@link OperatorProcessor}, which contains color information about {@link SetColor} operators
	 */
	private OperatorProcessor processOperatorAndReturnProcessor(Operator operator, List<COSBase> operands)
			throws IOException
	{
		String name = operator.getName();
		OperatorProcessor processor = operators.get(name);
		if (processor != null)
		{
			processor.setContext(this);
			try
			{
				processor.process(operator, operands);
			} catch (IOException e)
			{
				operatorException(operator, operands, e);
			}
		} else
		{
			unsupportedOperator(operator, operands);
		}

		return processor;
	}

	@Override
	public void close()
	{
		// close the stream handler, which shuts down the thread, writes all pending changes, closes any open resources,
		// and deletes any temp files
		streamHandler.close();
	}

	@Override
	public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) throws IOException
	{
	}

	@Override
	public void drawImage(PDImage pdImage) throws IOException
	{
	}

	@Override
	public void clip(int windingRule) throws IOException
	{
	}

	@Override
	public void moveTo(float x, float y) throws IOException
	{
	}

	@Override
	public void lineTo(float x, float y) throws IOException
	{
	}

	@Override
	public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException
	{
	}

	@Override
	public Point2D getCurrentPoint() throws IOException
	{
		return null;
	}

	@Override
	public void closePath() throws IOException
	{
	}

	@Override
	public void endPath() throws IOException
	{
	}

	@Override
	public void strokePath() throws IOException
	{
	}

	@Override
	public void fillPath(int windingRule) throws IOException
	{
	}

	@Override
	public void fillAndStrokePath(int windingRule) throws IOException
	{
	}

	@Override
	public void shadingFill(COSName shadingName) throws IOException
	{
	}

	/**
	 * Basic Object containing the start and end bounds of the SetColor operator and parameters, the PDColor which the
	 * operator encodes, and whether or not the operator is stroking.
	 * 
	 * @author Steven Fontaine
	 */
	private static final class SetColorOperatorStreamSlice
	{
		private final long start;
		private final long end;
		private final PDColor color;
		private final boolean stroking;

		private SetColorOperatorStreamSlice(long start, long end, PDColor color, boolean stroking)
		{
			this.start = start;
			this.end = end;
			this.color = color;
			this.stroking = stroking;
		}
	}

	/**
	 * <p>
	 * Inverts all colors of every page in {@code doc}, sets the background of each page to the RGB color given by
	 * {@code background} (or if {@code background} is null, preserves the default white background), and, if
	 * {@code invertImages} is true, inverts every image on every page.
	 * </p>
	 * <p>
	 * Specifically, this method iterates through each page, processes each page's content streams (see §7.8.2 of the
	 * <a href="https://www.adobe.com/content/dam/acom/en/devnet/pdf/pdfs/PDF32000_2008.pdf">PDF specifications</a>),
	 * determines the locations of each "{@link SetColor}" operation (see §8.6.8), and replaces these operations with
	 * an {@code RG} operation whose parameters are the inverse of the corresponding {@code SetColor} operation. Next it
	 * prepends a rectangle the size of the PDF page whose fill color is equal to the RGB value given in
	 * {@code background}. Finally, it iterates through each image in the page and inverts the image.
	 * </p>
	 * 
	 */
	// @SuppressWarnings("deprecation")
	public static final void invert(PDDocument doc, float[] background, boolean invertImages) throws IOException
	{
		assert (background == null || background.length == 3);
		int numPages = doc.getNumberOfPages();
		int pn = 0;
		for (PDPage page: doc.getPages())
		{
			System.out.println("Page " + (++pn) + "/" + numPages);
			try (PDFColorInverter inverter = new PDFColorInverter(page, new StreamHandler(doc), background))
			{
				inverter.processPage(page);

				if (invertImages)
				{
					for (PDContentStream stream: inverter.contentStreamSet)
						invertImagesOnPage(doc, stream);
				}
			}
		}
	}

	/**
	 * Iterates through all the resources on this {@code page}, and, if it's an image, inverts all the colors.
	 */
	private static void invertImagesOnPage(PDDocument doc, PDContentStream page) throws IOException
	{
		// get resources
		PDResources resources = page.getResources();
		if (resources == null)
			return;

		// iterate over resource names
		for (COSName name: resources.getXObjectNames())
		{
			// get the corresponding object
			PDXObject obj = resources.getXObject(name);

			// check if it's an image
			if (obj instanceof PDImageXObject)
			{
				// invert it
				System.out.println("Inverting image \"" + name.getName() + "\"");
				BufferedImage img = ((PDImageXObject) obj).getImage();
				invertImage(img);

				// replace the old one.
				PDImageXObject inverted = LosslessFactory.createFromImage(doc, img);
				inverted.setInterpolate(true);
				resources.put(name, inverted);
			}
		}
	}

	private static void invertImage(BufferedImage img)
	{
		for (int i = 0; i < img.getWidth(); i++)
		{
			for (int j = 0; j < img.getHeight(); j++)
			{
				int rgb = img.getRGB(i, j);
				// invert the rgb without touching the alpha channel
				img.setRGB(i, j, rgb ^ 0xffffff);
			}
		}
	}
}
