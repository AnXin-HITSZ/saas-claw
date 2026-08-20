"""MySQL 访问：runtime 读 agent/skill/tool 配置；仅 agent_file 行由人格直写工具 upsert，其余只读。"""
from sqlalchemy import BigInteger, Double, Integer, String, Text, create_engine, select
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, sessionmaker

from .config import settings


class Base(DeclarativeBase):
    pass


class Agent(Base):
    __tablename__ = 'agent'
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    claw_id: Mapped[int] = mapped_column(BigInteger)
    user_id: Mapped[int] = mapped_column(BigInteger)
    name: Mapped[str] = mapped_column(String(64))
    alias: Mapped[str] = mapped_column(String(64))
    description: Mapped[str | None] = mapped_column(String(512))
    system_prompt: Mapped[str | None] = mapped_column(Text)
    base_model: Mapped[str] = mapped_column(String(64))
    temperature: Mapped[float] = mapped_column(Double, default=0.7)
    max_tokens: Mapped[int] = mapped_column(default=4096)
    status: Mapped[int] = mapped_column(Integer, default=1)
    template: Mapped[str] = mapped_column(String(32), default="react")


class AgentFile(Base):
    __tablename__ = "agent_file"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    agent_id: Mapped[int] = mapped_column(BigInteger)
    file_name: Mapped[str] = mapped_column(String(128))
    file_url: Mapped[str] = mapped_column(String(512))
    file_type: Mapped[str | None] = mapped_column(String(32))
    file_size: Mapped[int] = mapped_column(BigInteger, default=0)
    file_hash: Mapped[str | None] = mapped_column(String(64))


class ModelConfig(Base):
    __tablename__ = "model_config"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    name: Mapped[str] = mapped_column(String(64))
    provider: Mapped[str] = mapped_column(String(32))
    model_name: Mapped[str] = mapped_column(String(64))
    endpoint: Mapped[str] = mapped_column(String(255))
    api_key: Mapped[str | None] = mapped_column(String(255))
    status: Mapped[int] = mapped_column(Integer, default=1)


class Tool(Base):
    __tablename__ = "tool"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    name: Mapped[str] = mapped_column(String(64))
    description: Mapped[str | None] = mapped_column(String(512))
    schema_json: Mapped[str | None] = mapped_column(Text)
    is_sensitive: Mapped[int] = mapped_column(Integer, default=0)
    status: Mapped[int] = mapped_column(Integer, default=1)


class Skill(Base):
    __tablename__ = "skill"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id: Mapped[int] = mapped_column(BigInteger, default=0)
    name: Mapped[str] = mapped_column(String(64))
    description: Mapped[str] = mapped_column(String(512))
    status: Mapped[int] = mapped_column(Integer, default=1)


class SkillFile(Base):
    __tablename__ = "skill_file"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    skill_id: Mapped[int] = mapped_column(BigInteger)
    file_name: Mapped[str] = mapped_column(String(128))
    file_url: Mapped[str] = mapped_column(String(512))
    file_size: Mapped[int] = mapped_column(BigInteger, default=0)
    file_hash: Mapped[str | None] = mapped_column(String(64))


class AgentSkill(Base):
    __tablename__ = "agent_skill"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    agent_id: Mapped[int] = mapped_column(BigInteger)
    skill_id: Mapped[int] = mapped_column(BigInteger)


engine = create_engine(
    f"mysql+pymysql://{settings.mysql_user}:{settings.mysql_password}"
    f"@{settings.mysql_host}:{settings.mysql_port}/{settings.mysql_database}"
    "?charset=utf8mb4",
    pool_pre_ping=True,
    pool_recycle=3600,
)
SessionLocal = sessionmaker(bind=engine, autoflush=False)


# ---- 查询函数（全部只读，status=1 才返回）----

def get_agent_by_alias(user_id: int, alias: str) -> Agent | None:
    with SessionLocal() as s:
        return s.scalars(select(Agent).where(
            Agent.user_id == user_id, Agent.alias == alias, Agent.status == 1
        )).first()


def get_agent_by_id(agent_id: int) -> Agent | None:
    with SessionLocal() as s:
        return s.scalars(select(Agent).where(
            Agent.id == agent_id, Agent.status == 1
        )).first()


def get_agents_by_claw(claw_id: int) -> list[Agent]:
    """路由用：本 Claw 所有 Agent（description 作路由目录）"""
    with SessionLocal() as s:
        return list(s.scalars(select(Agent).where(
            Agent.claw_id == claw_id, Agent.status == 1
        )))


def get_agent_files(agent_id: int) -> list[AgentFile]:
    with SessionLocal() as s:
        return list(s.scalars(select(AgentFile).where(AgentFile.agent_id == agent_id)))


def upsert_agent_file(
    agent_id: int,
    file_name: str,
    file_url: str,
    file_size: int,
    file_hash: str,
    file_type: str | None = None,
) -> None:
    """人格直写后同步 agent_file 行（runtime 唯一写库路径，其余查询保持只读）。

    存在 (agent_id, file_name) 行则更新 url/type/size/hash，否则插入新行。
    必须更新 file_hash：runtime 的 _persona_cache 按 hash 比对，不更新则人格修改永不生效。
    """
    with SessionLocal() as s:
        row = s.scalars(select(AgentFile).where(
            AgentFile.agent_id == agent_id, AgentFile.file_name == file_name
        )).first()
        if row is None:
            s.add(AgentFile(
                agent_id=agent_id, file_name=file_name, file_url=file_url,
                file_type=file_type, file_size=file_size, file_hash=file_hash,
            ))
        else:
            row.file_url = file_url
            row.file_type = file_type
            row.file_size = file_size
            row.file_hash = file_hash
        s.commit()


def get_model_config(name: str) -> ModelConfig | None:
    """agent.base_model 引用的就是 model_config.name"""
    with SessionLocal() as s:
        return s.scalars(select(ModelConfig).where(
            ModelConfig.name == name, ModelConfig.status == 1
        )).first()


def get_tools() -> list[Tool]:
    with SessionLocal() as s:
        return list(s.scalars(select(Tool).where(Tool.status == 1)))


def get_agent_skills(agent_id: int) -> list[Skill]:
    """Agent 依赖的 Skill 列表（经 agent_skill 关联）"""
    with SessionLocal() as s:
        return list(s.scalars(select(Skill)
                             .join(AgentSkill, AgentSkill.skill_id == Skill.id)
                             .where(AgentSkill.agent_id == agent_id, Skill.status == 1)))


def get_skill_files(skill_id: int) -> list[SkillFile]:
    with SessionLocal() as s:
        return list(s.scalars(select(SkillFile).where(SkillFile.skill_id == skill_id)))