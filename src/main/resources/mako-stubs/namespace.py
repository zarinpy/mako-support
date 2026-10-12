class Namespace:
    attr: dict
    cache: object
    context: Context
    filename: str
    module: object
    uri: str
    template: object

    def get_namespace(self, uri: str):
        return Namespace()

    def get_template(self, uri: str):
        return None

    def get_cached(self, key):
        return None

    def include_file(self, uri: str, **kwargs):
        return None

    def __getattr__(self, name: str):
        return None
