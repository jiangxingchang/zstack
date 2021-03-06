package org.zstack.tag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.core.Platform;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.MessageSafe;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.db.SoftDeleteEntityExtensionPoint;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.header.AbstractService;
import org.zstack.header.apimediator.ApiMessageInterceptionException;
import org.zstack.header.apimediator.GlobalApiMessageInterceptor;
import org.zstack.header.cluster.ClusterVO;
import org.zstack.header.configuration.DiskOfferingVO;
import org.zstack.header.configuration.InstanceOfferingVO;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.host.HostVO;
import org.zstack.header.image.ImageVO;
import org.zstack.header.message.APICreateMessage;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.Message;
import org.zstack.header.network.l2.L2NetworkVO;
import org.zstack.header.network.l3.IpRangeVO;
import org.zstack.header.network.l3.L3NetworkVO;
import org.zstack.header.query.APIQueryReply;
import org.zstack.header.storage.backup.BackupStorageVO;
import org.zstack.header.storage.primary.PrimaryStorageVO;
import org.zstack.header.tag.*;
import org.zstack.header.vm.VmInstanceVO;
import org.zstack.header.volume.VolumeVO;
import org.zstack.header.zone.ZoneVO;
import org.zstack.query.QueryFacade;
import org.zstack.utils.*;
import org.zstack.utils.logging.CLogger;

import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.metamodel.EntityType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

import static org.zstack.utils.CollectionUtils.removeDuplicateFromList;

public class TagManagerImpl extends AbstractService implements TagManager,
        SoftDeleteEntityExtensionPoint, GlobalApiMessageInterceptor, SystemTagLifeCycleExtension {
    private static final CLogger logger = Utils.getLogger(TagManagerImpl.class);

    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private CloudBus bus;
    @Autowired
    private QueryFacade qf;
    @Autowired
    private ErrorFacade errf;
    @Autowired
    private PluginRegistry pluginRgty;

    private List<SystemTag> systemTags = new ArrayList<SystemTag>();
    private Map<String, List<SystemTag>> resourceTypeSystemTagMap = new HashMap<String, List<SystemTag>>();
    private Map<String, Class> resourceTypeClassMap = new HashMap<String, Class>();
    private Map<Class, Class> resourceTypeCreateMessageMap = new HashMap<Class, Class>();
    private Map<String, List<SystemTagCreateMessageValidator>> createMessageValidators = new HashMap<String, List<SystemTagCreateMessageValidator>>();
    private Map<String, List<SystemTagLifeCycleExtension>> lifeCycleExtensions = new HashMap<String, List<SystemTagLifeCycleExtension>>();

    private void initSystemTags() throws IllegalAccessException {
        List<Class> classes = BeanUtils.scanClass("org.zstack", TagDefinition.class);
        for (Class clz : classes) {
            List<Field> fields = FieldUtils.getAllFields(clz);
            for (Field f : fields) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    continue;
                }

                if (!SystemTag.class.isAssignableFrom(f.getType())) {
                    continue;
                }

                f.setAccessible(true);
                SystemTag stag = (SystemTag) f.get(null);
                if (stag == null) {
                    throw new CloudRuntimeException(String.format("%s.%s defines a null system tag", f.getDeclaringClass(), f.getName()));
                }

                if (PatternedSystemTag.class.isAssignableFrom(f.getType())) {
                    PatternedSystemTag ptag = new PatternedSystemTag(stag.getTagFormat(), stag.getResourceClass());
                    ptag.setValidators(stag.getValidators());
                    f.set(null, ptag);
                    systemTags.add(ptag);
                    stag = ptag;
                } else {
                    SystemTag sstag = new SystemTag(stag.getTagFormat(), stag.getResourceClass());
                    sstag.setValidators(stag.getValidators());
                    f.set(null, sstag);
                    systemTags.add(sstag);
                    stag = sstag;
                }

                stag.setTagMgr(this);
                List<SystemTag> lst = resourceTypeSystemTagMap.get(stag.getResourceClass().getSimpleName());
                if (lst == null) {
                    lst = new ArrayList<SystemTag>();
                    resourceTypeSystemTagMap.put(stag.getResourceClass().getSimpleName(), lst);
                }
                lst.add(stag);
            }
        }
    }

    void init() {
        for (EntityType<?> entity : dbf.getEntityManager().getMetamodel().getEntities()) {
            Class type =  entity.getJavaType();
            String name = type.getSimpleName();
            resourceTypeClassMap.put(name, type);
            logger.debug(String.format("discovered tag resource type[%s], class[%s]", name, type));
        }

        try {
            // this makes sure DatabaseFacade is injected into every SystemTag object
            initSystemTags();
        } catch (Exception e) {
            throw new CloudRuntimeException(e);
        }

        List<Class> createMessageClass = BeanUtils.scanClass("org.zstack", TagResourceType.class);
        for (Class cmsgClz : createMessageClass) {
            TagResourceType at = (TagResourceType) cmsgClz.getAnnotation(TagResourceType.class);
            Class resType = at.value();
            if (!resourceTypeClassMap.values().contains(resType)) {
                throw new CloudRuntimeException(String.format("tag resource type[%s] defined in @TagResourceType of class[%s] is not a VO entity",
                        resType.getName(), cmsgClz.getName()));
            }
            resourceTypeCreateMessageMap.put(cmsgClz, resType);
        }
    }

    private void populateExtensions() {
        for (SystemTagLifeCycleExtension ext : pluginRgty.getExtensionList(SystemTagLifeCycleExtension.class)) {
            for (String resType : ext.getResourceTypeForVirtualRouterSystemTags()) {
                if (!resourceTypeClassMap.containsKey(resType)) {
                    throw new CloudRuntimeException(String.format("%s returns a unknown resource type[%s] for system tag", ext.getClass(), resType));
                }

                List<SystemTagLifeCycleExtension> lst = lifeCycleExtensions.get(resType);
                if (lst == null) {
                    lst = new ArrayList<SystemTagLifeCycleExtension>();
                    lifeCycleExtensions.put(resType, lst);
                }

                lst.add(ext);
            }
        }
    }

    private boolean isTagExisting(String resourceUuid, String tag, TagType type, String resourceType) {
        if (type == TagType.User) {
            SimpleQuery<UserTagVO> q = dbf.createQuery(UserTagVO.class);
            q.add(UserTagVO_.resourceType, SimpleQuery.Op.EQ, resourceType);
            q.add(UserTagVO_.tag, SimpleQuery.Op.EQ, tag);
            q.add(UserTagVO_.resourceUuid, SimpleQuery.Op.EQ, resourceUuid);
            long count = q.count();
            return count != 0;
        } else {
            SimpleQuery<SystemTagVO> q = dbf.createQuery(SystemTagVO.class);
            q.add(UserTagVO_.resourceType, SimpleQuery.Op.EQ, resourceType);
            q.add(UserTagVO_.tag, SimpleQuery.Op.EQ, tag);
            q.add(UserTagVO_.resourceUuid, SimpleQuery.Op.EQ, resourceUuid);
            long count = q.count();
            return count != 0;
        }
    }

    private TagInventory createTag(String resourceUuid, String tag, TagType type, String resourceType) {
        if (!resourceTypeClassMap.keySet().contains(resourceType)) {
            throw new IllegalArgumentException(String.format("no resource type[%s] found for tag", resourceType));
        }

        if (isTagExisting(resourceUuid, tag, type, resourceType)) {
            return null;
        }

        if (type == TagType.User) {
            UserTagVO vo = new UserTagVO();
            vo.setResourceType(resourceType);
            vo.setResourceUuid(resourceUuid);
            vo.setUuid(Platform.getUuid());
            vo.setTag(tag);
            vo.setType(type);
            vo = dbf.persistAndRefresh(vo);
            return UserTagInventory.valueOf(vo);
        } else {
            SystemTagVO vo = new SystemTagVO();
            vo.setResourceType(resourceType);
            vo.setUuid(Platform.getUuid());
            vo.setResourceUuid(resourceUuid);
            vo.setInherent(true);
            vo.setTag(tag);
            vo.setType(type);
            vo = dbf.persistAndRefresh(vo);
            SystemTagInventory stag = SystemTagInventory.valueOf(vo);
            fireTagCreated(Arrays.asList(stag));
            return stag;
        }
    }

    @Override
    public SystemTagInventory createNonInherentSystemTag(String resourceUuid, String tag, String resourceType) {
        if (isTagExisting(resourceUuid, tag, TagType.System, resourceType)) {
            return null;
        }

        SystemTagVO vo = new SystemTagVO();
        vo.setResourceType(resourceType);
        vo.setUuid(Platform.getUuid());
        vo.setResourceUuid(resourceUuid);
        vo.setInherent(false);
        vo.setTag(tag);
        vo.setType(TagType.System);
        vo = dbf.persistAndRefresh(vo);
        SystemTagInventory inv = SystemTagInventory.valueOf(vo);
        fireTagCreated(Arrays.asList(inv));
        return inv;
    }

    @Override
    public void createTagsFromAPICreateMessage(APICreateMessage msg, String resourceUuid, String resourceType) {
        if (msg.getSystemTags() != null && !msg.getSystemTags().isEmpty()) {
            for (String sysTag : msg.getSystemTags()) {
                createNonInherentSystemTag(resourceUuid, sysTag, resourceType);
            }
        }
        if (msg.getUserTags() != null && !msg.getUserTags().isEmpty()) {
            for (String utag : msg.getUserTags()) {
                createUserTag(resourceUuid, utag, resourceType);
            }
        }
    }

    @Override
    public TagInventory createSysTag(String resourceUuid, String tag, String resourceType) {
        return createTag(resourceUuid, tag, TagType.System, resourceType);
    }

    @Override
    public TagInventory createUserTag(String resourceUuid, String tag, String resourceType) {
        return createTag(resourceUuid, tag, TagType.User, resourceType);
    }

    @Override
    public TagInventory createSysTag(String resourceUuid, Enum tag, String resourceType) {
        return createSysTag(resourceUuid, tag.toString(), resourceType);
    }

    @Override
    public TagInventory createUserTag(String resourceUuid, Enum tag, String resourceType) {
        return createUserTag(resourceUuid, tag.toString(), resourceType);
    }

    @Override
    @Transactional
    public void copySystemTag(String srcResourceUuid, String srcResourceType, String dstResourceUuid, String dstResourceType) {
        String sql = "select stag from SystemTagVO stag where stag.resourceUuid = :ruuid and stag.resourceType = :rtype and stag.inherent = :ih";
        TypedQuery<SystemTagVO> srcq = dbf.getEntityManager().createQuery(sql, SystemTagVO.class);
        srcq.setParameter("ruuid", srcResourceUuid);
        srcq.setParameter("rtype", srcResourceType);
        srcq.setParameter("ih", false);
        List<SystemTagVO> srctags = srcq.getResultList();
        if (srctags.isEmpty()) {
            return;
        }

        for (SystemTagVO stag : srctags) {
            SystemTagVO ntag = new SystemTagVO(stag);
            ntag.setUuid(Platform.getUuid());
            ntag.setResourceType(dstResourceType);
            ntag.setResourceUuid(dstResourceUuid);
            dbf.getEntityManager().persist(ntag);
        }
    }

    @Override
    public List<String> findSystemTags(String resourceUuid) {
        SimpleQuery<SystemTagVO> q = dbf.createQuery(SystemTagVO.class);
        q.select(SystemTagVO_.tag);
        q.add(SystemTagVO_.resourceUuid, SimpleQuery.Op.EQ, resourceUuid);
        return q.listValue();
    }

    @Override
    public List<String> findUserTags(String resourceUuid) {
        SimpleQuery<UserTagVO> q = dbf.createQuery(UserTagVO.class);
        q.select(UserTagVO_.tag);
        q.add(UserTagVO_.resourceUuid, SimpleQuery.Op.EQ, resourceUuid);
        return q.listValue();
    }

    private boolean hasTag(String resourceUuid, String tag, TagType tagType) {
        if (tagType == TagType.System) {
            SimpleQuery<SystemTagVO> q = dbf.createQuery(SystemTagVO.class);
            q.add(SystemTagVO_.resourceUuid, SimpleQuery.Op.EQ, resourceUuid);
            q.add(SystemTagVO_.tag, SimpleQuery.Op.EQ, tag);
            return q.isExists();
        } else {
            SimpleQuery<UserTagVO> q = dbf.createQuery(UserTagVO.class);
            q.add(UserTagVO_.resourceUuid, SimpleQuery.Op.EQ, resourceUuid);
            q.add(UserTagVO_.tag, SimpleQuery.Op.EQ, tag);
            return q.isExists();
        }
    }

    @Override
    public boolean hasSystemTag(String resourceUuid, String tag) {
        return hasTag(resourceUuid, tag, TagType.System);
    }

    @Override
    public boolean hasSystemTag(String resourceUuid, Enum tag) {
        return hasTag(resourceUuid, tag.toString(), TagType.System);
    }

    @Override
    public void deleteSystemTag(String tag, String resourceUuid, String resourceType, Boolean inherit) {
        deleteSystemTag(tag, resourceUuid, resourceType, inherit, false);
    }

    private void deleteSystemTag(String tag, String resourceUuid, String resourceType, Boolean inherit, boolean useLike) {
        DebugUtils.Assert(tag != null || resourceUuid != null || resourceType != null, String.format("tag, resourceUuid, resourceType cannot all be null"));
        SimpleQuery<SystemTagVO> q = dbf.createQuery(SystemTagVO.class);
        if (tag != null) {
            if (useLike) {
                q.add(SystemTagVO_.tag, Op.LIKE, tag);
            } else{
                q.add(SystemTagVO_.tag, Op.EQ, tag);
            }
        }
        if (resourceUuid != null) {
            q.add(SystemTagVO_.resourceUuid, Op.EQ, resourceUuid);
        }
        if (resourceType != null) {
            q.add(SystemTagVO_.resourceType, Op.EQ, resourceType);
        }
        if (inherit != null) {
            q.add(SystemTagVO_.inherent, Op.EQ, inherit);
        }

        List<SystemTagVO> vos = q.list();
        dbf.removeCollection(vos, SystemTagVO.class);
        if (!vos.isEmpty()) {
            fireTagDeleted(SystemTagInventory.valueOf(vos));
        }
    }

    @Override
    public void deleteSystemTagUseLike(String tag, String resourceUuid, String resourceType, Boolean inherit) {
        deleteSystemTag(tag, resourceUuid, resourceType, inherit, true);
    }

    private void fireTagDeleted(List<SystemTagInventory> tags) {
        for (SystemTagInventory tag : tags) {
            List<SystemTagLifeCycleExtension> exts = lifeCycleExtensions.get(tag.getResourceType());
            if (exts != null) {
                for (SystemTagLifeCycleExtension ext : exts) {
                    try {
                        ext.tagDeleted(tag);
                    } catch (Exception e) {
                        logger.warn(String.format("unhandled exception when calling %s", ext.getClass()), e);
                    }
                }
            }
        }
    }

    private void fireTagCreated(List<SystemTagInventory> tags) {
        for (SystemTagInventory tag : tags) {
            List<SystemTagLifeCycleExtension> exts = lifeCycleExtensions.get(tag.getResourceType());
            if (exts != null) {
                for (SystemTagLifeCycleExtension ext : exts) {
                    try {
                        ext.tagCreated(tag);
                    } catch (Exception e) {
                        logger.warn(String.format("unhandled exception when calling %s", ext.getClass()), e);
                    }
                }
            }
        }
    }

    @Override
    @MessageSafe
    public void handleMessage(Message msg) {
        if (msg instanceof APIMessage) {
            handleApiMessage((APIMessage)msg);
        } else {
            handleLocalMessage(msg);
        }
    }

    private void handleLocalMessage(Message msg) {
        bus.dealWithUnknownMessage(msg);
    }

    private void handleApiMessage(APIMessage msg) {
        if (msg instanceof APIQueryTagMsg) {
            handle((APIQueryTagMsg) msg);
        } else if (msg instanceof APICreateUserTagMsg) {
            handle((APICreateUserTagMsg) msg);
        } else if (msg instanceof APICreateSystemTagMsg) {
            handle((APICreateSystemTagMsg) msg);
        } else if (msg instanceof APIDeleteTagMsg) {
            handle((APIDeleteTagMsg) msg);
        } else {
            bus.dealWithUnknownMessage(msg);
        }
    }

    private void handle(APICreateSystemTagMsg msg) {
        APICreateSystemTagEvent evt = new APICreateSystemTagEvent(msg.getId());
        SystemTagInventory inv = createNonInherentSystemTag(msg.getResourceUuid(), msg.getTag(), msg.getResourceType());
        evt.setInventory(inv);
        bus.publish(evt);
    }

    private void handle(APICreateUserTagMsg msg) {
        APICreateUserTagEvent evt = new APICreateUserTagEvent(msg.getId());
        UserTagInventory inv = (UserTagInventory) createUserTag(msg.getResourceUuid(), msg.getTag(), msg.getResourceType());
        evt.setInventory(inv);
        bus.publish(evt);
    }

    private void handle(APIDeleteTagMsg msg) {
        APIDeleteTagEvent evt = new APIDeleteTagEvent(msg.getId());
        SystemTagVO stag = dbf.findByUuid(msg.getUuid(), SystemTagVO.class);
        dbf.removeByPrimaryKey(msg.getUuid(), SystemTagVO.class);
        dbf.removeByPrimaryKey(msg.getUuid(), UserTagVO.class);
        if (stag != null) {
            fireTagDeleted(Arrays.asList(SystemTagInventory.valueOf(stag)));
        }
        bus.publish(evt);
    }

    private void handle(APIQueryTagMsg msg) {
        Class tagClass = msg.isSystemTag() ? SystemTagInventory.class : UserTagInventory.class;
        if (msg.isCount()) {
            APIQueryReply reply = new APIQueryReply();
            long count = qf.count(msg, tagClass);
            reply.setTotal(count);
            bus.reply(msg, reply);
        } else {
            List invs = qf.query(msg, tagClass);
            APIQueryTagReply reply = new APIQueryTagReply();
            reply.setInventories(invs);
            bus.reply(msg, reply);
        }
    }

    @Override
    public Collection<String> getManagedEntityNames() {
        return resourceTypeClassMap.keySet();
    }

    @Override
    public void validateSystemTag(String resourceUuid, String resourceType, String tag) {
        boolean checked = false;
        for (SystemTag stag : systemTags) {
            if (stag.isMatch(tag)) {
                checked = true;
                stag.validate(resourceUuid, resourceTypeClassMap.get(resourceType), tag);
            }
        }

        if (!checked) {
            throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                    String.format("no system tag matches %s", tag)
            ));
        }
    }

    @Override
    public void installCreateMessageValidator(String resourceType, SystemTagCreateMessageValidator validator) {
        if (!resourceTypeClassMap.containsKey(resourceType)) {
            throw new CloudRuntimeException(String.format("cannot find resource type[%s] in tag system ", resourceType));
        }

        List<SystemTagCreateMessageValidator> validators = createMessageValidators.get(resourceType);
        if (validators == null) {
            validators = new ArrayList<SystemTagCreateMessageValidator>();
            createMessageValidators.put(resourceType, validators);
        }
        validators.add(validator);
    }

    @Override
    public String getId() {
        return bus.makeLocalServiceId(TagConstant.SERVICE_ID);
    }

    @Override
    public boolean start() {
        populateExtensions();
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public List<Class> getEntityClassForSoftDeleteEntityExtension() {
        List<Class> classes = new ArrayList<Class>();
        classes.add(ZoneVO.class);
        classes.add(ClusterVO.class);
        classes.add(HostVO.class);
        classes.add(VmInstanceVO.class);
        classes.add(PrimaryStorageVO.class);
        classes.add(BackupStorageVO.class);
        classes.add(L2NetworkVO.class);
        classes.add(L3NetworkVO.class);
        classes.add(ImageVO.class);
        classes.add(VolumeVO.class);
        classes.add(IpRangeVO.class);
        classes.add(InstanceOfferingVO.class);
        classes.add(DiskOfferingVO.class);
        return classes;
    }

    @Override
    @Transactional
    public void postSoftDelete(Collection entityIds, Class entityClass) {
        String sql = "delete from SystemTagVO s where s.resourceType = :resourceType and s.resourceUuid in (:resourceUuids)";
        Query q = dbf.getEntityManager().createQuery(sql);
        q.setParameter("resourceType", entityClass.getSimpleName());
        q.setParameter("resourceUuids", entityIds);
        q.executeUpdate();

        sql = "delete from UserTagVO s where s.resourceType = :resourceType and s.resourceUuid in (:resourceUuids)";
        q = dbf.getEntityManager().createQuery(sql);
        q.setParameter("resourceType", entityClass.getSimpleName());
        q.setParameter("resourceUuids", entityIds);
        q.executeUpdate();
    }

    @Override
    public List<Class> getMessageClassToIntercept() {
        return CollectionDSL.list((Class)APICreateMessage.class);
    }

    @Override
    public InterceptorPosition getPosition() {
        return InterceptorPosition.FRONT;
    }


    @Override
    public APIMessage intercept(APIMessage msg) throws ApiMessageInterceptionException {
        APICreateMessage cmsg = (APICreateMessage)msg;
        if (cmsg.getSystemTags() != null && !cmsg.getSystemTags().isEmpty()) {
            cmsg.setSystemTags(removeDuplicateFromList(cmsg.getSystemTags()));

            for (String tag : cmsg.getSystemTags()) {
                boolean checked = false;
                for (SystemTag stag : systemTags) {
                    if (stag.isMatch(tag)) {
                        checked = true;
                        break;
                    }
                }

                if (!checked) {
                    throw new ApiMessageInterceptionException(errf.stringToInvalidArgumentError(
                            String.format("no system tag matches %s", tag)
                    ));
                }
            }

            Class resourceType = resourceTypeCreateMessageMap.get(cmsg.getClass());
            if (resourceType == null) {
                throw new ApiMessageInterceptionException(errf.stringToInternalError(
                        String.format("API message[%s] doesn't define resource type by @TagResourceType", cmsg.getClass().getName())
                ));
            }

            List<SystemTagCreateMessageValidator> validators = createMessageValidators.get(resourceType.getSimpleName());
            if (validators != null && !validators.isEmpty()) {
                for (SystemTagCreateMessageValidator validator : validators) {
                    validator.validateSystemTagInCreateMessage(cmsg);
                }
            }
        }

        return msg;
    }

    @Override
    public List<String> getResourceTypeForVirtualRouterSystemTags() {
        List<String> lst = new ArrayList<String>();
        lst.addAll(resourceTypeClassMap.keySet());
        return lst;
    }

    @Override
    public void tagCreated(SystemTagInventory tag) {
        List<SystemTag> tags = resourceTypeSystemTagMap.get(tag.getResourceType());
        for (SystemTag stag : tags) {
            stag.fireLifeCycleListener(tag, false);
        }
    }

    @Override
    public void tagDeleted(SystemTagInventory tag) {
        List<SystemTag> tags = resourceTypeSystemTagMap.get(tag.getResourceType());
        for (SystemTag stag : tags) {
            stag.fireLifeCycleListener(tag, true);
        }
    }
}
