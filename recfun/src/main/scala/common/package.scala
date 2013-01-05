package object common {
  /** `???` can be used for marking methods that remain to be implemented.
   *  @throws  An `Error`
   */
  def ??? : Nothing = throw new Error("an implementation is missing")

  type ??? = Nothing
  type *** = Any
}
