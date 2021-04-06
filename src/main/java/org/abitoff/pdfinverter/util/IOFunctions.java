package org.abitoff.pdfinverter.util;

import java.io.IOException;

/**
 * Convenience functional interface-oriented class which facilitates used functional programming while preserving
 * exception propagation
 * 
 * @author Steven Fontaine
 */
public class IOFunctions
{

	/**
	 * Executes {@code sup} while ignoring all interrupts
	 * 
	 * @return the return value from {@code sup}
	 */
	public static <T> T ignoreInterrupts(InterruptSupplier<T> sup)
	{
		while (true)
		{
			try
			{
				return sup.get();
			} catch (InterruptedException e)
			{
				// ignore interrupts
			}
		}
	}

	/**
	 * {@link java.util.function.Function Function} which allows for {@link IOException}s
	 */
	public static interface IOFunction<T, R>
	{
		R apply(T t) throws IOException;
	}

	/**
	 * {@link java.util.function.Consumer Consumer} which allows for {@link IOException}s
	 */
	public static interface IOConsumer<T>
	{
		void accept(T t) throws IOException;
	}

	/**
	 * {@link java.util.function.Supplier Supplier} which allows for {@link InterruptedException}s
	 */
	public static interface InterruptSupplier<T>
	{
		T get() throws InterruptedException;
	}
}
