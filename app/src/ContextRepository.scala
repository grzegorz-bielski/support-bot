package supportbot

trait ContextRepository[F[_]]:
    def createOrUpdate(info: ContextInfo): F[Unit]
    def getAll: F[Vector[ContextInfo]]
    def get(contextId: ContextId): F[Option[ContextInfo]]
    def delete(id: ContextId): F[Unit]
