"""
delamain Gateway - Annotation, Bookmark and Tag Tools
"""

from typing import Optional

from ..logging_config import get_logger
from ..routing.request_router import get_from_jadx
from ..busy_tracker import with_busy_check

logger = get_logger("annotation_tools")


# ==================== Annotation Tools ====================

async def add_annotation(
    target_type: str,
    target_name: str,
    content: str,
    author: str = "anonymous",
    apk_hash: Optional[str] = None,
    instance_id: Optional[str] = None,
) -> dict:
    body = {
        "target_type": target_type,
        "target_name": target_name,
        "content": content,
        "author": author,
    }
    if apk_hash:
        body["apk_hash"] = apk_hash
    logger.info(f"add_annotation: type={target_type} name={target_name} author={author}")
    return await get_from_jadx("annotations", instance_id=instance_id, method="POST", json_body=body)


async def get_annotations(
    target_type: Optional[str] = None,
    target_name: Optional[str] = None,
    apk_hash: Optional[str] = None,
    instance_id: Optional[str] = None,
) -> dict:
    params: dict = {}
    if target_type:
        params["target_type"] = target_type
    if target_name:
        params["target_name"] = target_name
    if apk_hash:
        params["apk_hash"] = apk_hash
    logger.info(f"get_annotations: params={params}")
    return await get_from_jadx("annotations", params=params, instance_id=instance_id)


async def delete_annotation(annotation_id: int, instance_id: Optional[str] = None) -> dict:
    logger.info(f"delete_annotation: id={annotation_id}")
    return await get_from_jadx(f"annotations/{annotation_id}", instance_id=instance_id, method="DELETE")


# ==================== Bookmark Tools ====================

async def add_bookmark(
    target_type: str,
    target_name: str,
    label: str,
    note: str = "",
    author: str = "anonymous",
    apk_hash: Optional[str] = None,
    instance_id: Optional[str] = None,
) -> dict:
    body = {
        "target_type": target_type,
        "target_name": target_name,
        "label": label,
        "note": note,
        "author": author,
    }
    if apk_hash:
        body["apk_hash"] = apk_hash
    logger.info(f"add_bookmark: type={target_type} name={target_name} label={label}")
    return await get_from_jadx("bookmarks", instance_id=instance_id, method="POST", json_body=body)


async def list_bookmarks(
    target_type: Optional[str] = None,
    target_name: Optional[str] = None,
    apk_hash: Optional[str] = None,
    instance_id: Optional[str] = None,
) -> dict:
    params: dict = {}
    if target_type:
        params["target_type"] = target_type
    if target_name:
        params["target_name"] = target_name
    if apk_hash:
        params["apk_hash"] = apk_hash
    logger.info(f"list_bookmarks: params={params}")
    return await get_from_jadx("bookmarks", params=params, instance_id=instance_id)


async def delete_bookmark(bookmark_id: int, instance_id: Optional[str] = None) -> dict:
    logger.info(f"delete_bookmark: id={bookmark_id}")
    return await get_from_jadx(f"bookmarks/{bookmark_id}", instance_id=instance_id, method="DELETE")


# ==================== Tag Tools ====================

async def add_tag(
    target_type: str,
    target_name: str,
    tag: str,
    author: str = "anonymous",
    apk_hash: Optional[str] = None,
    instance_id: Optional[str] = None,
) -> dict:
    body = {
        "target_type": target_type,
        "target_name": target_name,
        "tag": tag,
        "author": author,
    }
    if apk_hash:
        body["apk_hash"] = apk_hash
    logger.info(f"add_tag: type={target_type} name={target_name} tag={tag}")
    return await get_from_jadx("tags", instance_id=instance_id, method="POST", json_body=body)


async def get_tags(
    target_type: Optional[str] = None,
    target_name: Optional[str] = None,
    apk_hash: Optional[str] = None,
    instance_id: Optional[str] = None,
) -> dict:
    params: dict = {}
    if target_type:
        params["target_type"] = target_type
    if target_name:
        params["target_name"] = target_name
    if apk_hash:
        params["apk_hash"] = apk_hash
    logger.info(f"get_tags: params={params}")
    return await get_from_jadx("tags", params=params, instance_id=instance_id)


async def delete_tag(tag_id: int, instance_id: Optional[str] = None) -> dict:
    logger.info(f"delete_tag: id={tag_id}")
    return await get_from_jadx(f"tags/{tag_id}", instance_id=instance_id, method="DELETE")


# ==================== Summary Tools ====================

async def get_analysis_summary(
    apk_hash: Optional[str] = None,
    instance_id: Optional[str] = None,
) -> dict:
    params: dict = {}
    if apk_hash:
        params["apk_hash"] = apk_hash
    logger.info(f"get_analysis_summary: apk_hash={apk_hash}")
    return await get_from_jadx("analysis-notes", params=params, instance_id=instance_id)


# Module-level references to avoid shadowing inside register
_add_annotation = add_annotation
_get_annotations = get_annotations
_delete_annotation = delete_annotation
_add_bookmark = add_bookmark
_list_bookmarks = list_bookmarks
_delete_bookmark = delete_bookmark
_add_tag = add_tag
_get_tags = get_tags
_delete_tag = delete_tag
_get_analysis_summary = get_analysis_summary


def register_annotation_tools(mcp):
    """Register annotation/bookmark/tag tools with the MCP server."""

    @mcp.tool()
    @with_busy_check
    async def add_annotation(
        target_type: str,
        target_name: str,
        content: str,
        author: str = "anonymous",
        apk_hash: Optional[str] = None,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Add a persistent text annotation to a class/method/field (stored in local SQLite).

        Args:
            target_type: class|method|field. target_name: Fully qualified name. content: Annotation text.
            author: Author name. apk_hash: Leave empty to use currently open APK. instance_id: Target JADX instance.
        Returns:
            dict: {success, id, target_type, target_name, apk_hash}
        """
        return await _add_annotation(
            target_type, target_name, content,
            author=author, apk_hash=apk_hash, instance_id=instance_id,
        )

    @mcp.tool()
    @with_busy_check
    async def get_annotations(
        target_type: Optional[str] = None,
        target_name: Optional[str] = None,
        apk_hash: Optional[str] = None,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Query annotations for the current APK with optional filters.

        Args:
            target_type: Filter by type. target_name: Filter by name. apk_hash: Filter by APK hash.
            instance_id: Target JADX instance name.
        Returns:
            dict: {annotations: [...], count}
        """
        return await _get_annotations(
            target_type=target_type, target_name=target_name,
            apk_hash=apk_hash, instance_id=instance_id,
        )

    @mcp.tool()
    @with_busy_check
    async def delete_annotation(annotation_id: int, instance_id: Optional[str] = None) -> dict:
        """Delete an annotation by its numeric ID (from get_annotations).

        Args:
            annotation_id: Numeric annotation ID. instance_id: Target JADX instance name.
        Returns:
            dict: {success, deleted_id}
        """
        return await _delete_annotation(annotation_id, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def add_bookmark(
        target_type: str,
        target_name: str,
        label: str,
        note: str = "",
        author: str = "anonymous",
        apk_hash: Optional[str] = None,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Create a labeled bookmark for an important code location (stored in local SQLite).

        Args:
            target_type: class|method|field. target_name: Fully qualified name. label: e.g. "crypto logic".
            note: Optional description. author: Author name. apk_hash: APK hash. instance_id: Target JADX instance.
        Returns:
            dict: {success, id, label, target_type, target_name, apk_hash}
        """
        return await _add_bookmark(
            target_type, target_name, label,
            note=note, author=author, apk_hash=apk_hash, instance_id=instance_id,
        )

    @mcp.tool()
    @with_busy_check
    async def list_bookmarks(
        target_type: Optional[str] = None,
        target_name: Optional[str] = None,
        apk_hash: Optional[str] = None,
        instance_id: Optional[str] = None,
    ) -> dict:
        """List bookmarks for the current APK with optional filters.

        Args:
            target_type: Filter by type. target_name: Filter by name. apk_hash: Filter by APK hash.
            instance_id: Target JADX instance name.
        Returns:
            dict: {bookmarks: [...], count}
        """
        return await _list_bookmarks(
            target_type=target_type, target_name=target_name,
            apk_hash=apk_hash, instance_id=instance_id,
        )

    @mcp.tool()
    @with_busy_check
    async def delete_bookmark(bookmark_id: int, instance_id: Optional[str] = None) -> dict:
        """Delete a bookmark by its numeric ID (from list_bookmarks).

        Args:
            bookmark_id: Numeric bookmark ID. instance_id: Target JADX instance name.
        Returns:
            dict: {success, deleted_id}
        """
        return await _delete_bookmark(bookmark_id, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def add_tag(
        target_type: str,
        target_name: str,
        tag: str,
        author: str = "anonymous",
        apk_hash: Optional[str] = None,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Add a classification tag to a code element (stored in local SQLite).

        Args:
            target_type: class|method|field. target_name: Fully qualified name. tag: e.g. "crypto", "network".
            author: Author name. apk_hash: APK hash. instance_id: Target JADX instance name.
        Returns:
            dict: {success, id, tag, target_type, target_name, apk_hash}
        """
        return await _add_tag(
            target_type, target_name, tag,
            author=author, apk_hash=apk_hash, instance_id=instance_id,
        )

    @mcp.tool()
    @with_busy_check
    async def get_tags(
        target_type: Optional[str] = None,
        target_name: Optional[str] = None,
        apk_hash: Optional[str] = None,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Query classification tags for the current APK with optional filters.

        Args:
            target_type: Filter by type. target_name: Filter by name. apk_hash: Filter by APK hash.
            instance_id: Target JADX instance name.
        Returns:
            dict: {tags: [...], count}
        """
        return await _get_tags(
            target_type=target_type, target_name=target_name,
            apk_hash=apk_hash, instance_id=instance_id,
        )

    @mcp.tool()
    @with_busy_check
    async def delete_tag(tag_id: int, instance_id: Optional[str] = None) -> dict:
        """Delete a tag by its numeric ID (from get_tags).

        Args:
            tag_id: Numeric tag ID. instance_id: Target JADX instance name.
        Returns:
            dict: {success, deleted_id}
        """
        return await _delete_tag(tag_id, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def get_analysis_summary(
        apk_hash: Optional[str] = None,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Get all annotations + bookmarks + tags for the current APK in one call. Use to restore analysis context.

        Args:
            apk_hash: APK hash (leave empty for currently open APK). instance_id: Target JADX instance name.
        Returns:
            dict: {apk_hash, annotations, bookmarks, tags, annotations_count, bookmarks_count, tags_count}
        """
        return await _get_analysis_summary(apk_hash=apk_hash, instance_id=instance_id)
