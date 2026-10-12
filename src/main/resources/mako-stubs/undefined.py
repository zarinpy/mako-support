class Undefined:
    def __bool__(self) -> bool:
        return False

    def __str__(self) -> str:
        raise NameError("Undefined")


STOP_RENDERING: str = ""
