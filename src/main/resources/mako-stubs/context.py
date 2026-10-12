class Context:
    def lookup(self, key: str):
        return None

    kwargs: dict

    def keys(self):
        return []

    def get(self, key: str, default=None):
        return default

    def write(self, text: str) -> None:
        pass

    def writer(self):
        return None

    namespaces: dict

    def __getitem__(self, key: str):
        return None
