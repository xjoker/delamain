"""
delamain Gateway - Android Resource Analysis Tools
"""

from typing import Optional

from ..routing.request_router import get_from_jadx
from ..pagination_utils import PaginationUtils
from ..busy_tracker import with_busy_check


async def get_android_manifest(chunk: int = 0) -> dict:
    params = {}
    if chunk > 0:
        params["chunk"] = str(chunk)
    return await get_from_jadx("manifest", params)


async def get_strings(
    mode: str = "summary",
    query: Optional[str] = None,
    key: Optional[str] = None,
    locale: str = "values",
    offset: int = 0,
    limit: int = 50,
) -> dict:
    params = {
        "mode": mode,
        "locale": locale,
        "offset": offset,
        "limit": limit,
    }
    if query is not None:
        params["query"] = query
    if key is not None:
        params["key"] = key
    return await get_from_jadx("strings", params)


async def get_all_resource_file_names(
    offset: int = 0,
    count: int = 0,
) -> dict:
    return await PaginationUtils.get_paginated_data(
        endpoint="list-all-resource-file-names",
        offset=offset,
        count=count,
        data_extractor=lambda parsed: parsed.get("files", []),
        fetch_function=lambda ep, params={}: get_from_jadx(ep, params),
    )


async def get_resource_file(
    resource_name: str,
    chunk: int = 0,
) -> dict:
    params = {"file_name": resource_name}
    if chunk > 0:
        params["chunk"] = str(chunk)
    return await get_from_jadx("get-resource-file", params)


async def get_file_info() -> dict:
    return await get_from_jadx("file-info")


async def get_config_strings(
    mode: str = "summary",
    query: Optional[str] = None,
    key: Optional[str] = None,
    file: Optional[str] = None,
) -> dict:
    params = {"mode": mode}
    if query:
        params["query"] = query
    if key:
        params["key"] = key
    if file:
        params["file"] = file
    return await get_from_jadx("config-strings", params)


async def get_package_classes(
    package: Optional[str] = None,
    auto: bool = False,
    include_inner: bool = True,
    offset: int = 0,
    count: int = 100,
) -> dict:
    params = {}
    if package:
        params["package"] = package
    if auto:
        params["auto"] = "true"
    if not include_inner:
        params["include_inner"] = "false"
    if offset:
        params["offset"] = str(offset)
    if count != 100:
        params["count"] = str(count)
    return await get_from_jadx("package-classes", params)


async def jar_get_manifest() -> dict:
    return await get_from_jadx("jar-manifest")


async def jar_get_services() -> dict:
    return await get_from_jadx("jar-services")


async def jar_get_entry_points() -> dict:
    return await get_from_jadx("jar-entry-points")


async def jar_get_dependencies() -> dict:
    return await get_from_jadx("jar-dependencies")


async def jar_get_bytecode(class_name: str) -> dict:
    return await get_from_jadx("jar-bytecode", {"class_name": class_name})


async def list_resources_by_type(
    resource_type: Optional[str] = None,
    offset: int = 0,
    limit: int = 50,
) -> dict:
    params = {"offset": str(offset), "limit": str(limit)}
    if resource_type:
        params["type"] = resource_type
    return await get_from_jadx("list-resources-by-type", params)


async def get_decoded_resource(
    file_name: str,
    chunk: int = 0,
) -> dict:
    params = {"file_name": file_name}
    if chunk > 0:
        params["chunk"] = str(chunk)
    return await get_from_jadx("get-decoded-resource", params)


async def resolve_resource_id(
    resource_id: Optional[str] = None,
    name: Optional[str] = None,
    offset: int = 0,
    limit: int = 100,
) -> dict:
    params = {"offset": str(offset), "limit": str(limit)}
    if resource_id:
        params["id"] = resource_id
    if name:
        params["name"] = name
    return await get_from_jadx("resolve-resource-id", params)


_get_android_manifest = get_android_manifest
_get_strings = get_strings
_get_all_resource_file_names = get_all_resource_file_names
_get_resource_file = get_resource_file
_get_file_info = get_file_info
_get_config_strings = get_config_strings
_get_package_classes = get_package_classes
_jar_get_manifest = jar_get_manifest
_jar_get_services = jar_get_services
_jar_get_entry_points = jar_get_entry_points
_jar_get_dependencies = jar_get_dependencies
_jar_get_bytecode = jar_get_bytecode
_list_resources_by_type = list_resources_by_type
_get_decoded_resource = get_decoded_resource
_resolve_resource_id = resolve_resource_id


def register_resource_tools(mcp):
    """Register resource analysis tools to MCP Server."""

    @mcp.tool()
    @with_busy_check
    async def get_android_manifest(chunk: int = 0) -> dict:
        """Retrieve AndroidManifest.xml content. Auto-chunked if >8KB.

        Args:
            chunk: 0=first, N=continue chunked response.
        Returns:
            dict: {content: str, _chunking: {...}} — call again with chunk=N if has_more=true.
        """
        return await _get_android_manifest(chunk=chunk)

    @mcp.tool()
    @with_busy_check
    async def get_strings(
        mode: str = "summary",
        query: Optional[str] = None,
        key: Optional[str] = None,
        locale: str = "values",
        offset: int = 0,
        limit: int = 50,
    ) -> dict:
        """Get APK strings. Modes: summary (default)|list|search (needs query)|get (needs key).

        Args:
            mode: summary|list|search|get. query: Keyword for search. key: Key name for get.
            locale: e.g. "values", "values-en". offset/limit: Pagination.
        Returns:
            dict: Mode-dependent; summary={total_strings, sample_keys}, search={matches: [{key, value}]}
        """
        return await _get_strings(
            mode=mode, query=query, key=key, locale=locale,
            offset=offset, limit=limit,
        )

    @mcp.tool()
    @with_busy_check
    async def get_all_resource_file_names(
        offset: int = 0,
        count: int = 0,
    ) -> dict:
        """List all resource file paths (layouts, drawables, etc.) with pagination.

        Args:
            offset: Pagination start. count: Max results (0=all).
        Returns:
            dict: {files: [str, ...], total: int, has_more: bool}
        """
        return await _get_all_resource_file_names(offset, count)

    @mcp.tool()
    @with_busy_check
    async def get_resource_file(
        resource_name: str,
        chunk: int = 0,
    ) -> dict:
        """Fetch resource file content by path. Auto-chunked if >8KB.

        Args:
            resource_name: Path like 'res/layout/activity_main.xml'. chunk: 0=first, N=continue.
        Returns:
            dict: {content: str, _chunking: {...}} — call again with chunk=N if has_more=true.
        """
        return await _get_resource_file(resource_name, chunk=chunk)

    @mcp.tool()
    @with_busy_check
    async def get_file_info() -> dict:
        """Get file type, class count, and recommended tools for the loaded APK/JAR. Call first when starting analysis.

        Returns:
            dict: {file_type, class_count, android_features, recommended_tools, apk_package, ...}
        """
        return await _get_file_info()

    @mcp.tool()
    @with_busy_check
    async def get_config_strings(
        mode: str = "summary",
        query: Optional[str] = None,
        key: Optional[str] = None,
        file: Optional[str] = None,
    ) -> dict:
        """Get config strings: APK=strings.xml info, JAR=.properties files with search/get.

        Args:
            mode: summary|search|get|all. query: Search keyword. key: Property key.
            file: Filter by .properties filename (JAR only).
        Returns:
            dict: {source_type: android_strings|java_properties, ...}
        """
        return await _get_config_strings(
            mode=mode, query=query, key=key, file=file,
        )

    @mcp.tool()
    @with_busy_check
    async def get_package_classes(
        package: Optional[str] = None,
        auto: bool = False,
        include_inner: bool = True,
        offset: int = 0,
        count: int = 100,
    ) -> dict:
        """Get classes by package prefix for APK or JAR. Use auto=true to detect main package from manifest.

        Args:
            package: Package prefix. auto: Auto-detect main package. include_inner: Include inner classes.
            offset/count: Pagination (max 500).
        Returns:
            dict: {package: str, classes: [{name, is_inner}], total_matched, has_more}
        """
        return await _get_package_classes(
            package=package, auto=auto, include_inner=include_inner,
            offset=offset, count=count,
        )

    @mcp.tool()
    @with_busy_check
    async def jar_get_manifest() -> dict:
        """Read META-INF/MANIFEST.MF from JAR: Main-Class, Implementation-Title/Version, Spring Boot attrs.

        Returns:
            dict: {main_class, implementation_title, all_attributes} or NOT_APPLICABLE for APK.
        """
        return await _jar_get_manifest()

    @mcp.tool()
    @with_busy_check
    async def jar_get_services() -> dict:
        """Read META-INF/services/* from JAR to discover SPI service providers (JDBC, logging, etc.).

        Returns:
            dict: {services: [{interface, implementations, count}]} or NOT_APPLICABLE for APK.
        """
        return await _jar_get_services()

    @mcp.tool()
    @with_busy_check
    async def jar_get_entry_points() -> dict:
        """Discover entry points in JAR: Main-Class, Spring Boot Start-Class, main() methods.

        Returns:
            dict: {primary_entry: str, entry_points: [{type, class, priority}]} or NOT_APPLICABLE.
        """
        return await _jar_get_entry_points()

    @mcp.tool()
    @with_busy_check
    async def jar_get_dependencies() -> dict:
        """Analyze JAR dependencies: Maven pom.properties, MANIFEST Class-Path, Spring Boot BOOT-INF/lib.

        Returns:
            dict: {group_id, artifact_id, version, dependencies: [{name, version, source}]} or NOT_APPLICABLE.
        """
        return await _jar_get_dependencies()

    @mcp.tool()
    @with_busy_check
    async def jar_get_bytecode(class_name: str) -> dict:
        """Get bytecode/class structure (javap-style) for APK or JAR classes.

        Args:
            class_name: Fully qualified class name.
        Returns:
            dict: {bytecode: str, field_count, method_count, file_type}
        """
        return await _jar_get_bytecode(class_name)

    @mcp.tool()
    @with_busy_check
    async def list_resources_by_type(
        resource_type: Optional[str] = None,
        offset: int = 0,
        limit: int = 50,
    ) -> dict:
        """List APK resource files, either as a type-distribution summary or filtered by type.

        Without resource_type: returns total_files + a type_distribution histogram (layout,
        drawable, values, xml, raw, menu, anim, color, mipmap, font, ...).
        With resource_type: returns the paginated list of file paths under that type
        (matches "res/<type>/..." and qualified variants like "res/<type>-v21/...").

        Args:
            resource_type: Filter e.g. "layout", "drawable", "values" (omit for summary).
            offset/limit: Pagination when resource_type is set (limit capped at 500).
        Returns:
            dict: Summary={type_distribution, total_files} or
            {files: [str, ...], total, offset, limit, has_more} when filtered.
        """
        return await _list_resources_by_type(
            resource_type=resource_type, offset=offset, limit=limit,
        )

    @mcp.tool()
    @with_busy_check
    async def get_decoded_resource(
        file_name: str,
        chunk: int = 0,
    ) -> dict:
        """Fetch the decoded text content of a resource file (e.g. binary XML layouts decoded
        to readable XML). Auto-chunked if >8KB. Use list_resources_by_type to discover file
        names first.

        Args:
            file_name: Resource path, e.g. "res/layout/activity_main.xml". chunk: 0=first,
            N=continue chunked response.
        Returns:
            dict: {content: str, file_name, resource_type, source, _chunking: {...}} — call
            again with chunk=N if has_more=true. 404-style {status: "not_found"} if undecodable.
        """
        return await _get_decoded_resource(file_name, chunk=chunk)

    @mcp.tool()
    @with_busy_check
    async def resolve_resource_id(
        resource_id: Optional[str] = None,
        name: Optional[str] = None,
        offset: int = 0,
        limit: int = 100,
    ) -> dict:
        """Resolve Android resource IDs to names and vice versa (resources.arsc lookup).

        Pass exactly one of resource_id/name; passing neither lists all id<->name mappings
        (paginated). resource_id accepts hex ("0x7f0a0000") or decimal ("2131361792"); name is
        matched as a substring against "<type>/<key>" (e.g. "layout/activity_main").

        Args:
            resource_id: Hex or decimal resource ID to resolve to a name.
            name: Substring to search for among resource names (returns matches, capped by limit).
            offset/limit: Pagination for the list-all mode (limit capped at 1000).
        Returns:
            dict: id->name: {found, type_name, key_name, full_name, id_hex, id_decimal}.
            name->id: {matches: [...], count}. list-all: {entries: [...], total, has_more}.
        """
        return await _resolve_resource_id(
            resource_id=resource_id, name=name, offset=offset, limit=limit,
        )
