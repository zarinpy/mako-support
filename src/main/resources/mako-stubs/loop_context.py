class LoopContext:
    index: int
    reverse_index: int
    first: bool
    last: bool
    even: bool
    odd: bool
    parent: "LoopContext"

    def cycle(self, *values):
        return None
