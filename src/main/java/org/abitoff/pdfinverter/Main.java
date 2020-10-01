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

package org.abitoff.pdfinverter;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.pdfbox.contentstream.PDFColorInverter;
import org.apache.pdfbox.pdmodel.PDDocument;

import com.github.ajalt.colormath.ConvertibleColor;
import com.github.ajalt.colormath.CssParseKt;

public class Main
{
	public static void main(String[] args) throws IOException
	{
		// prevent pdfbox from printing unnecessary output
		Logger.getLogger("org.apache.pdfbox").setLevel(Level.SEVERE);

		if (args[0] == null || args[1] == null)
		{
			usage();
			return;
		}

		File in = new File(args[0]);
		File out = new File(args[1]);
		if (!in.exists())
		{
			System.out.println("Warning: \"" + in + "\" does not exist!");
			usage();
			return;
		}

		// default to black
		Integer backgroundRGB = 0x000000;
		if (args.length > 2 && args[2] != null)
		{
			try
			{
				// parse css color string
				ConvertibleColor c = CssParseKt.fromCss(ConvertibleColor.Companion, args[2]);
				backgroundRGB = c.toRGB().toPackedInt();
				// the background is white, which is PDF's default, so we can ignore this.
				if (backgroundRGB == 0xffffff)
					backgroundRGB = null;
			} catch (Exception e)
			{
				System.err.println("Unrecognized color \"" + args[2] + "\" Defaulting to black.");
			}
		}

		boolean invertImages = (args.length > 3 && args[3] != null) ? Boolean.parseBoolean(args[3]) : true;

		// convert background to float array. in the future, i plan on allowing increased bit-depth. floats offer a bit
		// depth of about 24 bits in the range [0.0, 1.0], which is a greater bit depth than most standards i'm aware of
		float[] background = null;
		if (backgroundRGB != null)
		{
			float backgroundR = (backgroundRGB >> 16 & 255) / 255f;
			float backgroundG = (backgroundRGB >> 8 & 255) / 255f;
			float backgroundB = (backgroundRGB & 255) / 255f;
			background = new float[] {backgroundR, backgroundG, backgroundB};
		}

		System.out.println("Loading " + in.getAbsolutePath() + "...");
		try (PDDocument doc = PDDocument.load(in))
		{
			PDFColorInverter.invert(doc, background, invertImages);
			System.out.println("Saving " + out.getAbsolutePath() + "...");
			doc.save(out);
		}
	}

	private static void usage()
	{
		System.out.println("Usage: \nPDFInverter.jar [in.pdf] [out.pdf] (background) (invert_images)\n"
				+ "in.pdf: File to invert\n" + "out.pdf: Location for output file\n"
				+ "background: CSS color string for the background of the PDF pages. White by default.\n"
				+ "invert_images: Boolean which determines whether or not to invert all images in the PDF. \"true\" by default");
	}
}
