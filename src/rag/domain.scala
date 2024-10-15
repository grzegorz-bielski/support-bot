package supportbot

opaque type Model <: String = String
object Model:
  inline def apply(value: String): Model = value
