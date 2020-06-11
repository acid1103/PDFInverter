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

package org.apache.pdfbox.pdfparser;

import java.io.IOException;

import org.apache.pdfbox.contentstream.PDContentStream;

/**
 * <p>
 * An extension of {@link PDFStreamParser} which simply provides {@link PDFStreamColorSlicer#getNextSlice()}.
 * </p>
 * <p>
 * This class must be in the package {@code org.apache.pdfbox.pdfparser} in order to have visibility to
 * {@link BaseParser#seqSource}. Visibility to {@code seqSource} is required in order to determine the bounds for
 * {@link StreamSlice}s.
 * </p>
 * 
 * @see PDFStreamColorSlicer#getNextSlice()
 * 
 * @author Steven Fontaine
 */
public class PDFStreamColorSlicer extends PDFStreamParser
{
	public PDFStreamColorSlicer(PDContentStream page) throws IOException
	{
		super(page);
	}

	/**
	 * Very similar to {@link PDFStreamParser#parseNextToken()}, except it wraps the next token into a
	 * {@link StreamSlice}.
	 */
	public StreamSlice getNextSlice() throws IOException
	{
		long start = this.seqSource.getPosition();
		Object token = super.parseNextToken();
		long end = this.seqSource.getPosition();
		return new StreamSlice(token, (int) start, (int) end);
	}

	/**
	 * Simple class containing a {@link PDFStreamParser#parseNextToken() token} as well as its bounds in the underlying
	 * content stream.
	 * 
	 * @author Steven Fontaine
	 */
	public static final class StreamSlice
	{
		public final Object token;
		public final int start;
		public final int end;

		public StreamSlice(Object token, int start, int end)
		{
			this.token = token;
			this.start = start;
			this.end = end;
		}
	}
}
