package sjm

/** A value that is either an error ([Left]) or a result ([Right]). */
sealed interface Either<out E, out A> {
    data class Left<out E>(val value: E) : Either<E, Nothing>
    data class Right<out A>(val value: A) : Either<Nothing, A>
}

inline fun <E, A, B> Either<E, A>.map(f: (A) -> B): Either<E, B> =
    when (this) {
        is Either.Left -> this
        is Either.Right -> Either.Right(f(value))
    }

inline fun <E, A, B> Either<E, A>.flatMap(f: (A) -> Either<E, B>): Either<E, B> =
    when (this) {
        is Either.Left -> this
        is Either.Right -> f(value)
    }

inline fun <E, F, A> Either<E, A>.mapLeft(f: (E) -> F): Either<F, A> =
    when (this) {
        is Either.Left -> Either.Left(f(value))
        is Either.Right -> this
    }

/** All results, or the first error. */
fun <E, A> List<Either<E, A>>.sequence(): Either<E, List<A>> =
    fold<Either<E, A>, Either<E, List<A>>>(Either.Right(emptyList())) { acc, next ->
        acc.flatMap { list -> next.map { list + it } }
    }

/** Wraps [block], turning any thrown exception into a [Left] using [onError]. */
inline fun <E, A> attempt(onError: (Throwable) -> E, block: () -> A): Either<E, A> =
    try {
        Either.Right(block())
    } catch (e: Throwable) {
        Either.Left(onError(e))
    }
