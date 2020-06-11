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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorProcessor;
import org.apache.pdfbox.contentstream.operator.color.SetColor;
import org.apache.pdfbox.contentstream.operator.color.SetColorOperatorUtils;
import org.apache.pdfbox.contentstream.operator.color.SetStrokingColor;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdfparser.PDFStreamColorSlicer;
import org.apache.pdfbox.pdfparser.PDFStreamColorSlicer.StreamSlice;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
public class PDFColorInverter extends PDFGraphicsStreamEngine
{
	/**
	 * List which, after calling {@link PDFColorInverter#processPage(PDPage)}, contains a
	 * {@link SetColorOperatorStreamSlice} representing each {@link SetColor} operator in this page's content stream.
	 */
	private final List<SetColorOperatorStreamSlice> colorOpSlices = new ArrayList<SetColorOperatorStreamSlice>();

	protected PDFColorInverter(PDPage page)
	{
		super(page);
	}

	/**
	 * Very similar to {@link PDFGraphicsStreamEngine#processStreamOperators(PDContentStream)}. Additionally, it finds
	 * the bounds of each {@link SetColor} operator, constructs a {@link SetColorOperatorStreamSlice}, and adds it to
	 * {@link PDFColorInverter#colorOpSlices}.
	 */
	protected void processStreamOperators(PDContentStream contentStream) throws IOException
	{
		List<StreamSlice> arguments = new ArrayList<StreamSlice>();
		PDFStreamColorSlicer parser = new PDFStreamColorSlicer(contentStream);
		StreamSlice slice = parser.getNextSlice();
		while (slice.token != null)
		{
			if (slice.token instanceof Operator)
			{
				OperatorProcessor processor =
						processOperatorAndReturnProcessor((Operator) slice.token, mapToCOSBaseList(arguments));
				if (processor instanceof SetColor)
					this.colorOpSlices.add(generateColorSlice(arguments, slice, (SetColor) processor));

				arguments.clear();
			} else
			{
				arguments.add(slice);
			}

			slice = parser.getNextSlice();
		}
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
		private final int start;
		private final int end;
		private final PDColor color;
		private final boolean stroking;

		private SetColorOperatorStreamSlice(int start, int end, PDColor color, boolean stroking)
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
	 * determines the locations of each "{@link SetColor}" operation (see §8.6.8), and replaces these operations with an
	 * {@code RG} operation whose parameters are the inverse of the corresponding {@code SetColor} operation. Next it
	 * prepends a rectangle the size of the PDF page whose fill color is equal to the RGB value given in
	 * {@code background}. Finally, it iterates through each image in the page and inverts the image.
	 * </p>
	 * 
	 */
	@SuppressWarnings("deprecation")
	public static final void invert(PDDocument doc, float[] background, boolean invertImages) throws IOException
	{
		assert (background == null || background.length == 3);
		for (PDPage page: doc.getPages())
		{
			String stream = new String(IOUtils.toByteArray(page.getContents()), StandardCharsets.UTF_8);
			StringBuilder invertedStreamBuilder = new StringBuilder();

			PDFColorInverter inverter = new PDFColorInverter(page);
			inverter.processPage(page);

			// iterate through the SetColor operations
			int start = 0;
			for (SetColorOperatorStreamSlice slice: inverter.colorOpSlices)
			{
				// copy the section of the content stream between the previous slice and the current slice into the
				// inverted stream
				invertedStreamBuilder.append(stream.substring(start, slice.start));

				PDColorSpace cs = slice.color.getColorSpace();
				String command;
				if (slice.color.getColorSpace() instanceof PDPattern)
				{
					System.err.println("Patterns aren't currently supported!");
					// patterns aren't currently supported, so just use the old operation
					command = stream.substring(slice.start, slice.end);
				} else
				{
					// float representation of rgb gives ~24 bits per component
					float[] rgb = cs.toRGB(slice.color.getComponents());
					System.out.print(Arrays.toString(rgb) + " -> ");
					// invert the rgb values
					rgb[0] = 1f - rgb[0];
					rgb[1] = 1f - rgb[1];
					rgb[2] = 1f - rgb[2];
					System.out.println(Arrays.toString(rgb));

					// construct the new operation
					command = " " + rgb[0] + " " + rgb[1] + " " + rgb[2] + " " + (slice.stroking ? "RG " : "rg ");
				}

				// append the new operation to the new stream
				invertedStreamBuilder.append(command);
				start = slice.end;
			}
			// append the final slice of the old stream
			invertedStreamBuilder.append(stream.subSequence(start, stream.length()));

			// modify the current content stream of the current page in OVERWRITE mode
			try (PDPageContentStream contentStream =
					new PDPageContentStream(doc, page, AppendMode.OVERWRITE, true, false))
			{
				// if background isn't null, insert the background rectangle
				if (background != null)
				{
					contentStream.setNonStrokingColor(background[0], background[1], background[2]);
					PDRectangle rect = page.getMediaBox();
					contentStream.addRect(0, 0, rect.getWidth(), rect.getHeight());
					contentStream.fill();
				}
				// insert the new content stream
				contentStream.appendRawCommands(invertedStreamBuilder.toString().getBytes(StandardCharsets.UTF_8));
			}

			if (invertImages)
				invertImagesOnPage(doc, page);
		}
	}

	/**
	 * Iterates through all the resources on this {@code page}, and, if it's an image, inverts all the colors.
	 */
	private static void invertImagesOnPage(PDDocument doc, PDPage page) throws IOException
	{
		// get resources
		PDResources resources = page.getResources();

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
